/*
 *
 *  * Copyright 2000-2014 JetBrains s.r.o.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package jetbrains.buildServer.clouds.vmware;

import com.intellij.openapi.diagnostic.Logger;
import com.vmware.vim25.mo.Task;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import jetbrains.buildServer.clouds.*;
import jetbrains.buildServer.clouds.base.AbstractCloudImage;
import jetbrains.buildServer.clouds.base.connector.AbstractInstance;
import jetbrains.buildServer.clouds.base.connector.CloudAsyncTaskExecutor;
import jetbrains.buildServer.clouds.base.connector.TaskCallbackHandler;
import jetbrains.buildServer.clouds.vmware.errors.VmwareCheckedCloudException;
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo;
import jetbrains.buildServer.clouds.vmware.connector.VMWareApiConnector;
import jetbrains.buildServer.clouds.vmware.connector.VmwareInstance;
import jetbrains.buildServer.clouds.vmware.connector.VmwareTaskWrapper;
import org.jetbrains.annotations.NotNull;

/**
 * @author Sergey.Pak
 *         Date: 4/15/2014
 *         Time: 3:58 PM
 */
public class VmwareCloudImage extends AbstractCloudImage<VmwareCloudInstance, VmwareCloudImageDetails>{

  private static final Logger LOG = Logger.getInstance(VmwareCloudImage.class.getName());

  private final VMWareApiConnector myApiConnector;
  @NotNull private final CloudAsyncTaskExecutor myAsyncTaskExecutor;
  private final VmwareCloudImageDetails myImageDetails;

  public VmwareCloudImage(@NotNull final VMWareApiConnector apiConnector,
                          @NotNull final VmwareCloudImageDetails imageDetails,
                          @NotNull final CloudAsyncTaskExecutor asyncTaskExecutor) {
    super(imageDetails.getSourceName(), imageDetails.getSourceName());
    myImageDetails = imageDetails;
    myApiConnector = apiConnector;
    myAsyncTaskExecutor = asyncTaskExecutor;
    myInstances.clear();
    Map<String, VmwareInstance> realInstances = null;
    try {
      realInstances = myApiConnector.listImageInstances(this);
    } catch (VmwareCheckedCloudException e) {
      updateErrors(TypedCloudErrorInfo.fromException(e));
      return;
    }
    if (imageDetails.getBehaviour().isUseOriginal()) {
      final VmwareCloudInstance imageInstance = new VmwareCloudInstance(this, imageDetails.getSourceName(), VmwareConstants.CURRENT_STATE);
      myInstances.put(myImageDetails.getSourceName(), imageInstance);

      final VmwareInstance vmwareInstance = realInstances.get(imageDetails.getSourceName());
      if (vmwareInstance != null) {
        imageInstance.setStatus(vmwareInstance.getInstanceStatus());
      } else {
        imageInstance.setStatus(InstanceStatus.UNKNOWN);
        imageInstance.updateErrors(new TypedCloudErrorInfo("NoVM", "VM doesn't exist: " + imageDetails.getSourceName()));
      }
    } else {
      for (String instanceName : realInstances.keySet()) {
        final VmwareInstance instance = realInstances.get(instanceName);
        final String snapshotName = instance.getProperty(VMWareApiConnector.TEAMCITY_VMWARE_IMAGE_SNAPSHOT);
        VmwareCloudInstance cloudInstance = new VmwareCloudInstance(this, instanceName, snapshotName);
        cloudInstance.setStatus(instance.getInstanceStatus());
        myInstances.put(instanceName, cloudInstance);
      }
    }
  }

  @NotNull
  public String getSnapshotName() {
    return myImageDetails.getSnapshotName();
  }

  private synchronized VmwareCloudInstance getOrCreateInstance() throws VmwareCheckedCloudException {
    if (!canStartNewInstance()){
      throw new QuotaException("Unable to start more instances of image " + getName());
    }

    if (myImageDetails.getBehaviour().isUseOriginal()) {
      LOG.info("Won't create a new instance - using original");
      return myInstances.get(myImageDetails.getSourceName());
    }

    final String latestSnapshotName = myApiConnector.getLatestSnapshot(myImageDetails.getSourceName(), myImageDetails.getSnapshotName());
    if (!myImageDetails.useCurrentVersion() && latestSnapshotName == null) {
      updateErrors(new TypedCloudErrorInfo("No such snapshot: " + getSnapshotName()));
      throw new VmwareCheckedCloudException("Unable to find snapshot: " + myImageDetails.getSnapshotName());
    }

    if (!myImageDetails.getBehaviour().isDeleteAfterStop()) {
      // on demand clone
      final Map<String, VmwareInstance> vmClones = myApiConnector.listImageInstances(this);

      // start an existsing one.
      final VmwareInstance imageVm = myApiConnector.getInstanceDetails(myImageDetails.getSourceName());
      for (VmwareInstance vmInstance : vmClones.values()) {
        if (vmInstance.getInstanceStatus() == InstanceStatus.STOPPED) {

          final String vmName = vmInstance.getName();
          final VmwareCloudInstance instance = myInstances.get(vmName);

          if (instance == null) {
            LOG.warn("Unable to find instance " + vmName + " in myInstances.");
            continue;
          }

            // checking if this instance is already starting.
          if (instance.getStatus() != InstanceStatus.STOPPED)
            continue;

          if (myImageDetails.useCurrentVersion()) {
            if (imageVm.getChangeVersion() == null || !imageVm.getChangeVersion().equals(vmInstance.getChangeVersion())) {
              LOG.info(String.format("Change version for %s is outdated: '%s' vs '%s'", vmName, vmInstance.getChangeVersion(), imageVm.getChangeVersion()));
              deleteInstance(instance);
              continue;
            }
          } else {
            final String snapshotName = vmInstance.getProperty(VMWareApiConnector.TEAMCITY_VMWARE_IMAGE_SNAPSHOT);
            if (!latestSnapshotName.equals(snapshotName)) {
              LOG.info(String.format("VM %s Snapshot is not the latest one: '%s' vs '%s'", vmName, snapshotName, latestSnapshotName));
              deleteInstance(instance);
              continue;
            }
          }
          LOG.info("Will use existing VM with name " + vmName);
          return instance;
        }
      }
    }
    // wasn't able to find an existing candidate, so will clone into a new VM
    final String newVmName = generateNewVmName();
    LOG.info("Will create a new VM with name " + newVmName);
    return new VmwareCloudInstance(this, newVmName, latestSnapshotName);
  }

  @Override
  public synchronized VmwareCloudInstance startNewInstance(@NotNull final CloudInstanceUserData cloudInstanceUserData) throws QuotaException {
    try {
      final VmwareCloudInstance instance = getOrCreateInstance();
      boolean willClone = !myApiConnector.checkVirtualMachineExists(instance.getName());
      LOG.info("Will clone for " + instance.getName() + ": " + willClone);
      if (willClone && myImageDetails.getMaxInstances() <= myInstances.size()){
        throw new QuotaException(String.format("Cannot clone '%s' into '%s' - limit exceeded", myImageDetails.getSourceName(), instance.getName()));
      }
      instance.setStatus(InstanceStatus.SCHEDULED_TO_START);
      if (!myInstances.containsKey(instance.getName())) {
        addInstance(instance);
      }
      if (willClone) {
        myAsyncTaskExecutor.executeAsync(
          new VmwareTaskWrapper(new Callable<Task>() {
          public Task call() throws Exception {
            return myApiConnector.cloneVm(instance, myImageDetails.getResourcePoolName(), myImageDetails.getFolderName());
          }}
          ),
          new ImageStatusTaskWrapper(instance) {
          @Override
          public void onSuccess() {
            cloneVmSuccessHandler(instance, cloudInstanceUserData);
          }

          @Override
          public void onError(final Throwable th) {
            super.onError(th);
            removeInstance(instance.getName());
          }
        });
      } else {
        cloneVmSuccessHandler(instance, cloudInstanceUserData);
      }
      return instance;
    } catch (QuotaException e) {
      throw e;
    } catch (VmwareCheckedCloudException e) {
      throw new CloudException("Unable to start new instance: " + e.toString());
    }
  }

  private synchronized void cloneVmSuccessHandler(@NotNull final VmwareCloudInstance instance, @NotNull final CloudInstanceUserData cloudInstanceUserData) {
    instance.setStatus(InstanceStatus.STARTING);
    myAsyncTaskExecutor.executeAsync(new VmwareTaskWrapper(new Callable<Task>() {
      public Task call() throws Exception {
        return myApiConnector.startInstance(instance, instance.getName(), cloudInstanceUserData);
      }
    }), new ImageStatusTaskWrapper(instance) {
      @Override
      public void onSuccess() {
        reconfigureVmTask(instance, cloudInstanceUserData);
      }
    });
  }

  private synchronized void reconfigureVmTask(@NotNull final VmwareCloudInstance instance, @NotNull final CloudInstanceUserData cloudInstanceUserData) {
    myAsyncTaskExecutor.executeAsync(new VmwareTaskWrapper(new Callable<Task>() {
      public Task call() throws Exception {
        return myApiConnector.reconfigureInstance(instance, instance.getName(), cloudInstanceUserData);
      }
    }), new ImageStatusTaskWrapper(instance) {
      @Override
      public void onSuccess() {
        instance.setStatus(InstanceStatus.RUNNING);
        instance.updateErrors();
        LOG.info("Instance started successfully");
      }
    });
  }


  public void terminateInstance(@NotNull final VmwareCloudInstance instance) {
    LOG.info("Stopping instance " + instance.getName());
    instance.setStatus(InstanceStatus.SCHEDULED_TO_STOP);
    myAsyncTaskExecutor.executeAsync(new VmwareTaskWrapper(new Callable<Task>() {
      public Task call() throws Exception {
        return myApiConnector.stopInstance(instance);
      }
    }), new ImageStatusTaskWrapper(instance){

      @Override
      public void onComplete() {
        instance.setStatus(InstanceStatus.STOPPED);
        if (myImageDetails.getBehaviour().isDeleteAfterStop()) { // we only destroy proper instances.
          deleteInstance(instance);
        }
      }
    });

  }

  private void deleteInstance(@NotNull final VmwareCloudInstance instance){
    if (instance.getErrorInfo() == null) {
      LOG.info("Will delete instance " + instance.getName());
      final VmwareInstance vmInstance;
      try {
        vmInstance = myApiConnector.getInstanceDetails(instance.getName());
        if (vmInstance != null) {
          myAsyncTaskExecutor.executeAsync(vmInstance.deleteInstance(), new TaskCallbackHandler() {
            @Override
            public void onSuccess() {
              super.onSuccess();
              removeInstance(instance.getName());
            }
          });
        }
      } catch (VmwareCheckedCloudException e) {
        LOG.warn("An exception during deleting instance " + instance.getName(), e);
        instance.updateErrors(TypedCloudErrorInfo.fromException(e));
      }
    } else {
      LOG.warn(String.format("Won't delete instance %s with error: %s (%s)",
                             instance.getName(), instance.getErrorInfo().getMessage(), instance.getErrorInfo().getDetailedMessage()));
    }
  }

  public boolean canStartNewInstance() {
    if (getErrorInfo() != null){
      LOG.debug("Can't start new instance, if image is erroneous");
        return false;
    }

    if (myImageDetails.getBehaviour().isUseOriginal()) {
      final VmwareCloudInstance myInstance = myInstances.get(myImageDetails.getSourceName());
      if (myInstance == null){
      return false;
    }
      return myInstance.getStatus() == InstanceStatus.STOPPED;
    }

    final List<String> runningInstancesNames = new ArrayList<String>();
    for (Map.Entry<String, VmwareCloudInstance> entry : myInstances.entrySet()) {
      if (entry.getValue().getStatus() != InstanceStatus.STOPPED)
        runningInstancesNames.add(entry.getKey());
    }
    final boolean canStartMore =  runningInstancesNames.size() < myImageDetails.getMaxInstances();
    LOG.debug(String.format("Running count: %d %s, can start more: %s",
                           runningInstancesNames.size(), Arrays.toString(runningInstancesNames.toArray()), String.valueOf(canStartMore)));
    return canStartMore;
  }

  @Override
  public void restartInstance(@NotNull final VmwareCloudInstance instance) {
    throw new UnsupportedOperationException("Restart not implemented");
  }

  private String generateNewVmName() {
    Random r = new Random();
    SimpleDateFormat sdf = new SimpleDateFormat("MMdd-HHmmss");
    return String.format("%s-clone-%s%s", getId(), sdf.format(new Date()), Integer.toHexString(r.nextInt(256)));
  }

  public void addInstance(@NotNull final VmwareCloudInstance instance){
    LOG.info(String.format("Image %s, put instance %s", myImageDetails.getSourceName(), instance.getName()));
    myInstances.put(instance.getName(), instance);
  }

  public void removeInstance(@NotNull final String name){
    LOG.info(String.format("Image %s, remove instance %s", myImageDetails.getSourceName(), name));
    myInstances.remove(name);
  }

  public VmwareCloudImageDetails getImageDetails() {
    return myImageDetails;
  }

  @Override
  public void detectNewInstances(final Map<String, AbstractInstance> realInstances) {
    for (String instanceName : realInstances.keySet()) {
      if (myInstances.get(instanceName) == null) {
        final VmwareInstance realInstance = (VmwareInstance)realInstances.get(instanceName);
        final VmwareCloudInstance newInstance = new VmwareCloudInstance(this, instanceName, realInstance.getSnapshotName());
        newInstance.setStatus(realInstance.getInstanceStatus());
        myInstances.put(instanceName, newInstance);
      }
    }
  }

  private static class ImageStatusTaskWrapper extends TaskCallbackHandler {

    @NotNull protected final VmwareCloudInstance myInstance;

    public ImageStatusTaskWrapper(@NotNull final VmwareCloudInstance instance) {
      myInstance = instance;
    }

    @Override
    public void onError(final Throwable th) {
      if (th != null) {
        myInstance.updateErrors(TypedCloudErrorInfo.fromException(th));
        LOG.warn("An error occured: " + th.getLocalizedMessage() + " during processing " + myInstance.getName());
      } else {
        myInstance.updateErrors(new TypedCloudErrorInfo("Unknown error during processing instance " + myInstance.getName()));
        LOG.warn("Unknown error during processing " + myInstance.getName());
      }
    }
  }
}
