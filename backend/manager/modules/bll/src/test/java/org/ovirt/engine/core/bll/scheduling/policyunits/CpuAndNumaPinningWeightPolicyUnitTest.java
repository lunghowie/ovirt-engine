package org.ovirt.engine.core.bll.scheduling.policyunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ovirt.engine.core.bll.scheduling.SchedulingContext;
import org.ovirt.engine.core.bll.scheduling.SchedulingParameters;
import org.ovirt.engine.core.bll.scheduling.pending.PendingResourceManager;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.NumaTuneMode;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.VdsNumaNodeDao;
import org.ovirt.engine.core.dao.VmNumaNodeDao;
import org.ovirt.engine.core.utils.MockConfigDescriptor;
import org.ovirt.engine.core.utils.MockConfigExtension;

@ExtendWith({MockitoExtension.class, MockConfigExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
public class CpuAndNumaPinningWeightPolicyUnitTest extends NumaPolicyTestBase {

    private static final long NODE_SIZE = 1024;

    public static Stream<MockConfigDescriptor<?>> mockConfiguration() {
        return Stream.of(
                MockConfigDescriptor.of(ConfigValues.MaxSchedulerWeight, 1000)
        );
    }

    @Mock
    private VmNumaNodeDao vmNumaNodeDao;
    @Mock
    private VdsNumaNodeDao vdsNumaNodeDao;

    private VM vm;
    private VDS hostWithoutNuma;
    private VDS hostWithOneCpuPerNode;
    private VDS hostWithTwoCpusPerNode;

    private List<VDS> hosts;

    @InjectMocks
    private CpuAndNumaPinningWeightPolicyUnit unit = new CpuAndNumaPinningWeightPolicyUnit(null, new PendingResourceManager());

    @BeforeEach
    public void setUp() {
        vm = new VM();
        vm.setId(Guid.newGuid());
        vm.setNumOfSockets(2);
        vm.setCpuPerSocket(1);
        vm.setThreadsPerCpu(1);
        vm.setNumaTuneMode(NumaTuneMode.STRICT);

        doAnswer(arg -> vm.getvNumaNodeList()).when(vmNumaNodeDao).getAllVmNumaNodeByVmId(any(Guid.class));

        hostWithoutNuma = createHost(0, NODE_SIZE);
        hostWithOneCpuPerNode = createHost(2, NODE_SIZE, 1);
        hostWithTwoCpusPerNode = createHost(2, NODE_SIZE, 2);

        hosts = Arrays.asList(hostWithoutNuma, hostWithOneCpuPerNode, hostWithTwoCpusPerNode);

        doAnswer(invocation -> hosts.stream()
                .filter(h -> h.getId().equals(invocation.getArgument(0)))
                .findAny()
                .map(VDS::getNumaNodeList).orElse(Collections.emptyList())
        ).when(vdsNumaNodeDao).getAllVdsNumaNodeByVdsId(any(Guid.class));
    }

    @Test
    public void testNoVmNuma() {
        vm.setCpuPinning("0#0_1#1");
        vm.setvNumaNodeList(Collections.emptyList());

        assertThat(score()).extracting("first", "second").contains(
                tuple(hostWithoutNuma.getId(), 1),
                tuple(hostWithOneCpuPerNode.getId(), 1),
                tuple(hostWithTwoCpusPerNode.getId(), 1)
        );
    }

    @Test
    public void testNoNumaPinning() {
        vm.setCpuPinning("0#0_1#1");
        vm.setvNumaNodeList(Arrays.asList(
                createVmNode(NODE_SIZE, 0, Collections.emptyList(), Arrays.asList(0)),
                createVmNode(NODE_SIZE, 1, Collections.emptyList(), Arrays.asList(1))
        ));

        assertThat(score()).extracting("first", "second").contains(
                tuple(hostWithoutNuma.getId(), 1),
                tuple(hostWithOneCpuPerNode.getId(), 1),
                tuple(hostWithTwoCpusPerNode.getId(), 1)
        );
    }

    @Test
    public void testNoCpuPinning() {
        vm.setCpuPinning(null);
        vm.setvNumaNodeList(Arrays.asList(
                createVmNode(NODE_SIZE, 0, Arrays.asList(0), Arrays.asList(0)),
                createVmNode(NODE_SIZE, 1, Arrays.asList(1), Arrays.asList(1))
        ));

        assertThat(score()).extracting("first", "second").contains(
                tuple(hostWithoutNuma.getId(), 1),
                tuple(hostWithOneCpuPerNode.getId(), 1),
                tuple(hostWithTwoCpusPerNode.getId(), 1)
        );
    }

    @Test
    public void testCpuAndNumaPinning() {
        vm.setCpuPinning("0#0_1#1");
        vm.setvNumaNodeList(Arrays.asList(
                createVmNode(NODE_SIZE, 0, Arrays.asList(0), Arrays.asList(0)),
                createVmNode(NODE_SIZE, 1, Arrays.asList(1), Arrays.asList(1))
        ));

        assertThat(score()).extracting("first", "second").contains(
                tuple(hostWithoutNuma.getId(), 1000),
                tuple(hostWithOneCpuPerNode.getId(), 1),
                tuple(hostWithTwoCpusPerNode.getId(), 1000)
        );
    }

    private List<Pair<Guid, Integer>> score() {
        return unit.score(new SchedulingContext(new Cluster(), Collections.emptyMap(), new SchedulingParameters()), hosts, vm);
    }
}
