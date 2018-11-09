/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azurecompute.arm.compute.strategy;

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.filterValues;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jclouds.azurecompute.arm.compute.AzureComputeServiceAdapter.AUTOGENERATED_IP_KEY;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.TIMEOUT_RESOURCE_DELETED;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.NOT_IN_RESOURCE_GROUP;
import static org.jclouds.azurecompute.arm.domain.IdReference.extractName;
import static org.jclouds.azurecompute.arm.domain.IdReference.extractResourceGroup;
import static org.jclouds.util.Predicates2.retry;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.collect.Sets;
import org.jclouds.azurecompute.arm.AzureComputeApi;
import org.jclouds.azurecompute.arm.compute.domain.ResourceGroupAndName;
import org.jclouds.azurecompute.arm.domain.AvailabilitySet;
import org.jclouds.azurecompute.arm.domain.DataDisk;
import org.jclouds.azurecompute.arm.domain.IdReference;
import org.jclouds.azurecompute.arm.domain.IpConfiguration;
import org.jclouds.azurecompute.arm.domain.ManagedDiskParameters;
import org.jclouds.azurecompute.arm.domain.NetworkInterfaceCard;
import org.jclouds.azurecompute.arm.domain.NetworkProfile.NetworkInterface;
import org.jclouds.azurecompute.arm.domain.NetworkSecurityGroup;
import org.jclouds.azurecompute.arm.domain.OSDisk;
import org.jclouds.azurecompute.arm.domain.PublicIPAddress;
import org.jclouds.azurecompute.arm.domain.VirtualMachine;
import org.jclouds.azurecompute.arm.domain.VirtualNetwork;
import org.jclouds.azurecompute.arm.features.NetworkSecurityGroupApi;
import org.jclouds.compute.functions.GroupNamingConvention;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.javax.annotation.Nullable;
import org.jclouds.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;

@Singleton
public class CleanupResources {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final AzureComputeApi api;
   private final Predicate<URI> resourceDeleted;
   private final Predicate<IdReference> notInResourceGroup;
   private final GroupNamingConvention.Factory namingConvention;

   @Inject
   CleanupResources(AzureComputeApi azureComputeApi, @Named(TIMEOUT_RESOURCE_DELETED) Predicate<URI> resourceDeleted,
         GroupNamingConvention.Factory namingConvention, @Named(NOT_IN_RESOURCE_GROUP) Predicate<IdReference> notInResourceGroup) {
      this.api = azureComputeApi;
      this.resourceDeleted = resourceDeleted;
      this.notInResourceGroup = notInResourceGroup;
      this.namingConvention = namingConvention;
   }

   public boolean cleanupNode(final String id) {
      ResourceGroupAndName resourceGroupAndName = ResourceGroupAndName.fromSlashEncoded(id);
      String resourceGroupName = resourceGroupAndName.resourceGroup();

      VirtualMachine virtualMachine = api.getVirtualMachineApi(resourceGroupName).get(resourceGroupAndName.name());
      if (virtualMachine == null) {
         return true;
      }

      logger.debug(">> destroying %s ...", id);
      boolean vmDeleted = deleteVirtualMachine(resourceGroupName, virtualMachine);

      cleanupVirtualMachineNICs(virtualMachine);
      cleanupManagedDisks(virtualMachine);
      cleanupAvailabilitySetIfOrphaned(virtualMachine);
      cleanupVirtualNetworks(resourceGroupName);

      return vmDeleted;
   }

   public boolean cleanupVirtualMachineNICs(VirtualMachine virtualMachine) {
      boolean deleted = true;
      for (NetworkInterface nicRef : virtualMachine.properties().networkProfile().networkInterfaces()) {
         String nicResourceGroup = extractResourceGroup(nicRef.id());
         String nicName = extractName(nicRef.id());
         NetworkInterfaceCard nic = api.getNetworkInterfaceCardApi(nicResourceGroup).get(nicName);

         Iterable<IdReference> publicIps = getPublicIps(nic);

         logger.debug(">> destroying nic %s...", nicName);
         URI nicDeletionURI = api.getNetworkInterfaceCardApi(nicResourceGroup).delete(nicName);
         boolean nicDeleted = nicDeletionURI == null || resourceDeleted.apply(nicDeletionURI);
         if (!nicDeleted) {
            logger.warn(">> nic not deleted %s...", nicName);
         }
         deleted &= nicDeleted;

         for (IdReference publicIp : publicIps) {
            String publicIpResourceGroup = publicIp.resourceGroup();
            String publicIpName = publicIp.name();

            PublicIPAddress ip = api.getPublicIPAddressApi(publicIpResourceGroup).get(publicIpName);
            if (ip.tags() != null && Boolean.parseBoolean(ip.tags().get(AUTOGENERATED_IP_KEY))) {
               logger.debug(">> deleting public ip %s...", publicIpName);
               try {
                  boolean ipDeleted = api.getPublicIPAddressApi(publicIpResourceGroup).delete(publicIpName);
                  if (!ipDeleted) {
                     logger.warn(">> ip not deleted %s...", ip);
                  }
                  deleted &= ipDeleted;
               } catch (Exception ex) {
                  deleted = false;
                  logger.warn(">> Error deleting ip %s: %s", ip, ex);
               }
            } else {
               logger.trace(">> not deleting public ip %s as %s key not present", ip, AUTOGENERATED_IP_KEY);
            }
         }
      }
      return deleted;
   }

   public boolean cleanupManagedDisks(VirtualMachine virtualMachine) {
      Map<String, URI> deleteJobs = new HashMap<String, URI>();
      Set<IdReference> deleteDisks = new HashSet<IdReference>();

      OSDisk osDisk = virtualMachine.properties().storageProfile().osDisk();
      deleteManagedDisk(osDisk.managedDiskParameters(), deleteJobs, deleteDisks);

      for (DataDisk dataDisk : virtualMachine.properties().storageProfile().dataDisks()) {
         deleteManagedDisk(dataDisk.managedDiskParameters(), deleteJobs, deleteDisks);
      }

      // Wait for the disk deletion jobs to complete
      Set<String> nonDeletedDisks = filterValues(deleteJobs, not(resourceDeleted)).keySet();
      if (!nonDeletedDisks.isEmpty()) {
         logger.warn(">> could not delete disks: %s", Joiner.on(',').join(nonDeletedDisks));
      }

      // Wait for 404 on the disk api
      Predicate<IdReference> diskDeleted = new Predicate<IdReference>() {
         @Override
         public boolean apply(IdReference input) {
            return api.getDiskApi(input.resourceGroup()).get(input.name()) == null;
         }
      };

      Predicate<IdReference> filter = retry(diskDeleted, 1200, 5, 15, SECONDS);
      Set<IdReference> nonDeleted = Sets.filter(deleteDisks, filter);

      if (!nonDeleted.isEmpty()) {
         logger.warn(">> disks not deleted: %s", Joiner.on(',').join(nonDeleted));
      }

      // Apply the `notInResourceGroup` predicate. This will poll the resource group API until the disk
      // is lo longer listed as a member of the resource group, which may occur for a short while
      // after the disk has been deleted
      Set<IdReference> disksNotInRg = Sets.filter(deleteDisks, notInResourceGroup);
      Set<IdReference> disksInRg = Sets.difference(deleteDisks, disksNotInRg);

      if (!disksInRg.isEmpty()) {
         logger.warn(">> disks still in resource group: %s", Joiner.on(',').join(nonDeleted));
      }

      return nonDeletedDisks.isEmpty();
   }

   private void deleteManagedDisk(@Nullable ManagedDiskParameters managedDisk, Map<String, URI> deleteJobs, Set<IdReference> deleteDisks) {
      if (managedDisk != null) {
         IdReference diskRef = IdReference.create(managedDisk.id());
         deleteDisks.add(diskRef);
         logger.debug(">> deleting managed disk %s...", diskRef.name());
         URI uri = api.getDiskApi(diskRef.resourceGroup()).delete(diskRef.name());
         if (uri != null) {
            deleteJobs.put(diskRef.name(), uri);
         }
      }
   }

   public boolean cleanupSecurityGroupIfOrphaned(String resourceGroup, String group) {
      String name = namingConvention.create().sharedNameForGroup(group);
      NetworkSecurityGroupApi sgapi = api.getNetworkSecurityGroupApi(resourceGroup);

      boolean deleted = false;

      try {
         NetworkSecurityGroup securityGroup = sgapi.get(name);
         if (securityGroup != null) {
            List<NetworkInterfaceCard> nics = securityGroup.properties().networkInterfaces();
            if (nics == null || nics.isEmpty()) {
               logger.debug(">> deleting orphaned security group %s from %s...", name, resourceGroup);
               try {
                  deleted = resourceDeleted.apply(sgapi.delete(name));
                  // Apply the `notInResourceGroup` predicate. This will poll the resource group API until the security group
                  // is lo longer listed as a member of the resource group, which may occur for a short while
                  // after the security group has been deleted
                  notInResourceGroup.apply(IdReference.create(securityGroup.id()));
               } catch (Exception ex) {
                  logger.warn(ex, ">> error deleting orphaned security group %s from %s...", name, resourceGroup);
               }
            } else {
               logger.trace(">> not deleting security group %s from %s is it has nics attached %s...", securityGroup, name, nics);
            }
         }
      } catch (Exception ex) {
         logger.warn(ex, "Error deleting security groups for %s and group %s", resourceGroup, group);
      }

      return deleted;
   }

   public boolean cleanupAvailabilitySetIfOrphaned(VirtualMachine virtualMachine) {
      boolean deleted = true;
      IdReference availabilitySetRef = virtualMachine.properties().availabilitySet();

      if (availabilitySetRef != null) {
         String name = availabilitySetRef.name();
         String resourceGroup = availabilitySetRef.resourceGroup();
         AvailabilitySet availabilitySet = api.getAvailabilitySetApi(resourceGroup).get(name);

         if (isOrphanedJcloudsAvailabilitySet(availabilitySet)) {
            logger.debug(">> deleting orphaned availability set %s from %s...", name, resourceGroup);
            URI uri = api.getAvailabilitySetApi(resourceGroup).delete(name);
            deleted = uri == null || resourceDeleted.apply(uri);
            if (!deleted) {
               logger.warn(">> availability set %s not deleted from %s...", name, resourceGroup);
            }
         }
      }

      return deleted;
   }

   private void cleanupVirtualNetworks(String resourceGroupName) {
      for (VirtualNetwork virtualNetwork : api.getVirtualNetworkApi(resourceGroupName).list()) {
         if (virtualNetwork.tags() != null && virtualNetwork.tags().containsKey("jclouds")) {
            try {
               // Virtual networks cannot be deleted if there are any resources attached. It does not seem to be possible
               // to list devices connected to a virtual network
               // https://docs.microsoft.com/en-us/azure/virtual-network/manage-virtual-network#delete-a-virtual-network
               // We also check the tags to ensure that it's jclouds-created
               api.getVirtualNetworkApi(resourceGroupName).delete(virtualNetwork.name());
               // Apply the `notInResourceGroup` predicate. This will poll the resource group API until the network
               // is lo longer listed as a member of the resource group, which may occur for a short while
               // after the network has been deleted
               notInResourceGroup.apply(IdReference.create(virtualNetwork.id()));
            } catch (IllegalArgumentException e) {
               if (e.getMessage().contains("InUseSubnetCannotBeDeleted")) {
                  logger.warn("Cannot delete virtual network %s as it is in use", virtualNetwork);
               } else {
                  throw e;
               }
            }
         }
      }
   }

   public boolean deleteResourceGroupIfEmpty(String group) {
      boolean deleted = false;
      List<org.jclouds.azurecompute.arm.domain.Resource> attachedResources = api.getResourceGroupApi().resources(group);

      if (attachedResources.isEmpty()) {
         logger.debug(">> the resource group %s is empty. Deleting...", group);
         URI uri = api.getResourceGroupApi().delete(group);
         deleted = uri == null || resourceDeleted.apply(uri);
      } else {
         logger.warn(">> the resource group %s contains %s. Not deleting...", group, attachedResources);
      }
      return deleted;
   }

   private Iterable<IdReference> getPublicIps(NetworkInterfaceCard nic) {
      return filter(transform(nic.properties().ipConfigurations(), new Function<IpConfiguration, IdReference>() {
         @Override
         public IdReference apply(IpConfiguration input) {
            return input.properties().publicIPAddress();
         }
      }), notNull());
   }

   private static boolean isOrphanedJcloudsAvailabilitySet(AvailabilitySet availabilitySet) {
      // We check for the presence of the 'jclouds' tag to make sure we only
      // delete availability sets that were automatically created by jclouds
      return availabilitySet != null
            && availabilitySet.tags() != null
            && availabilitySet.tags().containsKey("jclouds")
            && (availabilitySet.properties().virtualMachines() == null || availabilitySet.properties()
                  .virtualMachines().isEmpty());
   }

   private boolean deleteVirtualMachine(String group, VirtualMachine virtualMachine) {
      URI uri = api.getVirtualMachineApi(group).delete(virtualMachine.name());
      boolean deleted = uri == null || resourceDeleted.apply(uri);
      // Apply the `notInResourceGroup` predicate. This will poll the resource group API until the VM
      // is lo longer listed as a member of the resource group, which may occur for a short while
      // after the VM has been deleted
      notInResourceGroup.apply(IdReference.create(virtualMachine.id()));
      return deleted;
   }

}
