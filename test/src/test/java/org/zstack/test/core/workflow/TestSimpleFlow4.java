package org.zstack.test.core.workflow;

import junit.framework.Assert;
import org.junit.Test;
import org.zstack.core.workflow.Flow;
import org.zstack.core.workflow.FlowTrigger;
import org.zstack.core.workflow.SimpleFlowChain;
import org.zstack.core.workflow.WorkFlowException;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: frank
 * Time: 3:33 PM
 * To change this template use File | Settings | File Templates.
 */
public class TestSimpleFlow4 {
    CLogger logger = Utils.getLogger(TestSimpleFlow4.class);

    @Test
    public void test() throws WorkFlowException {
        final int[] count = {0};

        new SimpleFlowChain()
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        count[0] ++;
                        chain.next();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0] --;
                        chain.rollback();
                    }
                })
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        count[0]++;
                        chain.next();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0]--;
                        throw new RuntimeException("on purpose");
                    }
                })
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        chain.rollback();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0]--;
                    }
                })
                .start();

        Assert.assertEquals(0, count[0]);
    }
}
