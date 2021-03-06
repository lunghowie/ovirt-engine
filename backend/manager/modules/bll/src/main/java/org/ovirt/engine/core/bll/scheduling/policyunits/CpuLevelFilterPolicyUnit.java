package org.ovirt.engine.core.bll.scheduling.policyunits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.CpuFlagsManagerHandler;
import org.ovirt.engine.core.bll.scheduling.PolicyUnitImpl;
import org.ovirt.engine.core.bll.scheduling.SchedulingContext;
import org.ovirt.engine.core.bll.scheduling.SchedulingUnit;
import org.ovirt.engine.core.bll.scheduling.pending.PendingResourceManager;
import org.ovirt.engine.core.common.businessentities.ServerCpu;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.scheduling.PerHostMessages;
import org.ovirt.engine.core.common.scheduling.PolicyUnit;
import org.ovirt.engine.core.common.scheduling.PolicyUnitType;
import org.ovirt.engine.core.compat.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SchedulingUnit(
        guid = "438b052c-90ab-40e8-9be0-a22560202ea6",
        name = "CPU-Level",
        type = PolicyUnitType.FILTER,
        description = "Runs VMs only on hosts with a proper CPU level"
)
public class CpuLevelFilterPolicyUnit extends PolicyUnitImpl {
    private static final Logger log = LoggerFactory.getLogger(CpuLevelFilterPolicyUnit.class);

    @Inject
    private CpuFlagsManagerHandler cpuFlagsManagerHandler;

    public CpuLevelFilterPolicyUnit(PolicyUnit policyUnit,
            PendingResourceManager pendingResourceManager) {
        super(policyUnit, pendingResourceManager);
    }

    @Override
    public List<VDS> filter(SchedulingContext context, List<VDS> hosts, VM vm, PerHostMessages messages) {
        List<VDS> hostsToRunOn = new ArrayList<>();

        // CPU passthrough VM can be started everywhere
        if (vm.getRunOnVds() == null && vm.isUsingCpuPassthrough()) {
            hostsToRunOn.addAll(hosts);
            return hostsToRunOn;
        }

        String customCpu; // full name of the vm cpu
        Version compatibilityVer = vm.getCompatibilityVersion();

        // Migration checks for a VM with CPU passthrough.
        // In case of CPU passthrough enabled, the VM's CPU flags (the flags that the VM started running with)
        // should be identical to target host's CPU flags for migration to be allowed
        // TODO figure out how to handle hostModel
        if (vm.isUsingCpuPassthrough()
                && Objects.nonNull(vm.getCpuName())) {
            log.info("VM uses CPU flags passthrough, checking flags compatibility with: {}", vm.getCpuName());
            Set<String> requiredFlags = Arrays.stream(vm.getCpuName().split(","))
                    .collect(Collectors.toSet());

            for (VDS host : hosts) {
                Set<String> providedFlags = Arrays.stream(host.getCpuFlags().split(","))
                        .collect(Collectors.toSet());
                log.info("Host {} provides flags: {}", host.getName(), String.join(", ", providedFlags));

                if (requiredFlags.equals(providedFlags)) {
                    hostsToRunOn.add(host);
                } else {
                    log.info("Host {} can't run the VM because it's CPU flags are not exactly identical to VM's required CPU flags",
                            host.getName());
                }
            }
            return hostsToRunOn;
        }

        /* get required cpu name */
        if (StringUtils.isNotEmpty(vm.getCpuName())) { // dynamic check - used for 1.migrating vms 2.run-once 3.after dynamic field is updated with current static-field\cluster
            customCpu = vm.getCpuName();
        } else if (StringUtils.isNotEmpty(vm.getCustomCpuName())) { // static check - used only for cases where the dynamic value hasn't been updated yet(validate)
            customCpu = vm.getCustomCpuName();
        } else { // use cluster default - all hosts are valid
            return hosts;
        }

        customCpu = cpuFlagsManagerHandler.getCpuNameByCpuId(customCpu, compatibilityVer); // translate vdsVerb to full cpu name
        if(StringUtils.isNotEmpty(customCpu)) { // checks if there's a cpu with the given vdsVerb

            /* find compatible hosts */
            for (VDS host : hosts) {
                ServerCpu cpu = cpuFlagsManagerHandler.findMaxServerCpuByFlags(host.getCpuFlags(), compatibilityVer);
                String hostCpuName = cpu == null ? null : cpu.getCpuName();
                if (StringUtils.isNotEmpty(hostCpuName)) {
                    if (cpuFlagsManagerHandler.checkIfCpusSameManufacture(customCpu, hostCpuName, compatibilityVer)) { // verify comparison uses only one cpu-level scale
                        int compareResult = cpuFlagsManagerHandler.compareCpuLevels(customCpu, hostCpuName, compatibilityVer);
                        if (compareResult <= 0) {
                            hostsToRunOn.add(host);
                            log.debug("Host '{}' wasn't filtered out as it has a CPU level ({}) which is higher or equal than the CPU level the VM was run with ({})",
                                    host.getName(),
                                    hostCpuName,
                                    customCpu);
                        } else {
                            log.debug("Host '{}' was filtered out as it has a CPU level ({}) which is lower than the CPU level the VM was run with ({})",
                                    host.getName(),
                                    hostCpuName,
                                    customCpu);
                            messages.addMessage(host.getId(), String.format("$hostCPULevel %1$s", hostCpuName));
                            messages.addMessage(host.getId(), String.format("$vmCPULevel %1$s", customCpu));
                            messages.addMessage(host.getId(), EngineMessage.VAR__DETAIL__LOW_CPU_LEVEL.toString());
                        }
                    }
                }
            }
        }
        return hostsToRunOn;
    }
}
