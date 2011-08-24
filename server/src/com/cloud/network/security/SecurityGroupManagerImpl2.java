/**
 *  Copyright (C) 2011 Citrix Systems, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.network.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import com.cloud.agent.api.SecurityIngressRulesCmd;
import com.cloud.agent.manager.Commands;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.network.security.SecurityGroupWork.Step;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.Transaction;
import com.cloud.vm.VirtualMachine.State;

/**
 * Same as the base class -- except it uses the abstracted security group work queue
 *
 */
@Local(value={ SecurityGroupManager.class, SecurityGroupService.class })
public class SecurityGroupManagerImpl2 extends SecurityGroupManagerImpl {
    
    SecurityGroupWorkQueue _workQueue = new LocalSecurityGroupWorkQueue();
    
    
    WorkerThread[] _workers;
    long _timeToSleep = 10000;

    
    protected class WorkerThread extends Thread {
        public WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            work();
        }

    }

    @Override
    public void scheduleRulesetUpdateToHosts(List<Long> affectedVms, boolean updateSeqno, Long delayMs) {
        if (affectedVms.size() == 0) {
            return;
        }
        if (s_logger.isTraceEnabled()) {
            s_logger.trace("Security Group Mgr2: scheduling ruleset updates for " + affectedVms.size() + " vms");
        }
        Set<Long> workItems = new TreeSet<Long>();
        workItems.addAll(affectedVms);
    
        Transaction txn = Transaction.currentTxn();
        txn.start();
        _rulesetLogDao.createOrUpdate(workItems);
        txn.commit();
        _workQueue.submitWorkForVms(workItems);
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        // TODO Auto-generated method stub
        return super.configure(name, params);
    }

    @Override
    public boolean start() {
        // TODO Auto-generated method stub
        return super.start();
    }

    @Override
    public void work() {
        while (true) {
            try {
                s_logger.trace("Checking the work queue");
                List<SecurityGroupWork> workItems = _workQueue.getWork(1);
                
                for (SecurityGroupWork work: workItems) {
                    if (s_logger.isTraceEnabled()) {
                        s_logger.trace("Processing " + work.getInstanceId());
                    }

                    try {
                        VmRulesetLogVO rulesetLog = _rulesetLogDao.findByVmId(work.getInstanceId());
                        if (rulesetLog == null) {
                            s_logger.warn("Could not find ruleset log for vm " + work.getInstanceId());
                            continue;
                        }
                        work.setLogsequenceNumber(rulesetLog.getLogsequence());
                        sendRulesetUpdates(work);
                    }catch (Exception e) {
                        s_logger.error("Problem during SG work " + work, e);
                        work.setStep(Step.Error);
                    }
                }
            } catch (final Throwable th) {
                s_logger.error("Caught this throwable, ", th);
            } 
        }
    }
    
    protected void sendRulesetUpdates(SecurityGroupWork work){
        Long userVmId = work.getInstanceId();
        UserVm vm = _userVMDao.findById(userVmId);

        if (vm != null && vm.getState() == State.Running) {
            Map<PortAndProto, Set<String>> rules = generateRulesForVM(userVmId);
            Long agentId = vm.getHostId();
            if (agentId != null) {
                SecurityIngressRulesCmd cmd = generateRulesetCmd(vm.getInstanceName(), vm.getPrivateIpAddress(), 
                        vm.getPrivateMacAddress(), vm.getId(), generateRulesetSignature(rules), 
                        work.getLogsequenceNumber(), rules);
                Commands cmds = new Commands(cmd);
                try {
                    _agentMgr.send(agentId, cmds, _answerListener);
                } catch (AgentUnavailableException e) {
                    s_logger.debug("Unable to send updates for vm: " + userVmId + "(agentid=" + agentId + ")");
                }
            }
        } else {
            if (s_logger.isTraceEnabled()) {
                if (vm != null)
                    s_logger.trace("No rules sent to vm " + vm + "state=" + vm.getState());
                else
                    s_logger.trace("Could not find vm: No rules sent to vm " + userVmId );
            }
        }
    }

    @Override
    public void cleanupFinishedWork() {
        //no-op
    }
    

}
