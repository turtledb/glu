/*
 * Copyright 2010-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.linkedin.glu.console.services

import org.linkedin.glu.agent.api.Agent
import org.linkedin.glu.provisioner.core.environment.Environment
import org.linkedin.glu.agent.api.MountPoint
import org.linkedin.glu.provisioner.core.environment.Installation
import org.linkedin.glu.provisioner.core.fabric.InstallationDefinition
import org.linkedin.glu.provisioner.api.planner.IAgentPlanner
import org.linkedin.glu.provisioner.plan.api.Plan
import org.linkedin.glu.provisioner.core.action.ActionDescriptor
import org.linkedin.glu.console.domain.AuditLog
import org.apache.shiro.SecurityUtils
import org.linkedin.glu.console.domain.RoleName
import org.apache.shiro.authz.AuthorizationException
import org.linkedin.glu.console.domain.Fabric
import org.linkedin.groovy.util.state.StateMachine
import org.linkedin.groovy.util.state.StateMachineImpl
import org.linkedin.glu.agent.rest.client.AgentFactory
import org.linkedin.glu.provisioner.core.model.SystemModel
import org.linkedin.glu.provisioner.core.model.SystemEntry
import org.linkedin.util.lang.LangUtils
import org.linkedin.glu.agent.tracker.MountPointInfo
import org.linkedin.groovy.util.io.DataMaskingInputStream
import org.linkedin.glu.agent.tracker.AgentInfo
import org.linkedin.glu.provisioner.impl.agent.DefaultDescriptionProvider

/**
 * @author ypujante
 */
class AgentsService
{
  boolean transactional = false

  private final StateMachine stateMachine =
    new StateMachineImpl(transitions: Agent.DEFAULT_TRANSITIONS)

  private final def availableStates = stateMachine.availableStates as Set

  // will be dependency injected
  AgentFactory agentFactory
  TrackerService trackerService
  IAgentPlanner agentPlanner

  def getAllInfosWithAccuracy(Fabric fabric)
  {
    return trackerService.getAllInfosWithAccuracy(fabric)
  }

  def getAgentInfos(Fabric fabric)
  {
    return trackerService.getAgentInfos(fabric)
  }

  def getAgentInfo(Fabric fabric, String agentName)
  {
    return trackerService.getAgentInfo(fabric, agentName)
  }

  def getMountPointInfos(Fabric fabric, String agentName)
  {
    trackerService.getMountPointInfos(fabric, agentName)
  }

  def getMountPointInfo(Fabric fabric, String agentName, mountPoint)
  {
    trackerService.getMountPointInfo(fabric, agentName, mountPoint)
  }

  def getFullState(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.getFullState(args)
    }
  }

  def clearError(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.clearError', "${args}")
      agent.clearError(args)
    }
  }

  def uninstallScript(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.uninstallScript', "${args}")
      moveToState(agent, args.mountPoint, StateMachine.NONE, args.timeout)
      agent.uninstallScript(args)
    }
  }

  def forceUninstallScript(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.forceUninstallScript', "${args}")
      agent.uninstallScript(*:args, force: true)
    }
  }

  public Plan createTransitionPlan(args)
  {
    createTransitionPlan(args) { true }
  }

  public Plan createTransitionPlan(args, Closure filter)
  {
    def installations = args.installations
    if(args.id && args.mountPoint)
    {
      def mp = getMountPointInfo(args.fabric, args.id, args.mountPoint)
      if(mp)
        installations = toInstallations(args.fabric, [mp])
    }

    if(installations)
    {
      def state = args.state
      if(state instanceof String)
        state = [state]
      state = state.toList()
      return agentPlanner.createTransitionPlan(installations,
                                               state,
                                               DefaultDescriptionProvider.INSTANCE,
                                               filter)
    }

    return null
  }

  def interruptAction(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.interruptAction', "${args}")
      agent.interruptAction(args)
      agent.waitForState(mountPoint: args.mountPoint,
                         state: args.state,
                         timeout: args.timeout)
    }
  }

  def ps(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.ps()
    }
  }

  def sync(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.sync')
      agent.sync()
    }
  }

  def kill(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.kill', "${args}")
      agent.kill(args.pid as long, args.signal as int)
    }
  }

  void tailLog(args, Closure closure)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.tailLog', "${args}")
      closure(agent.tailAgentLog(args))
    }
  }

  void streamFileContent(args, Closure closure)
  {
    // do not allow non admin users to access files:
    // -- outside of /export/content/glu area
    // -- dir with conf or *Config as their name (as they may have sensitive data)
    if(!args.location.startsWith('/export/content/glu'))
    {
      try
      {
        SecurityUtils.getSubject().checkRole(RoleName.ADMIN.name())
      }
      catch (AuthorizationException e)
      {
        AuditLog.audit('agent.getFileContent.notAuthorized', "${args}")
        throw e
      }
    }
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      AuditLog.audit('agent.getFileContent', "${args}")
      def res = agent.getFileContent(args)
      if(res instanceof InputStream) {
        res = new DataMaskingInputStream(res)
      }

      closure(res)
    }
  }

  Plan<ActionDescriptor> createAgentsUpgradePlan(args)
  {
    return agentPlanner.createUpgradePlan(extractAgentsMap(args), args.version, args.coordinates)
  }

  private def extractAgentsMap(args)
  {
    def agentInfos = getAgentInfos(args.fabric)

    def agents = [:]

    args.agents.each { agentName ->
      AgentInfo agentInfo = agentInfos[agentName]
      if(agentInfo)
        agents[agentName] = agentInfo.URI
    }
    return agents
  }

  Plan<ActionDescriptor> createAgentsCleanupUpgradePlan(args)
  {
    return agentPlanner.createCleanupPlan(extractAgentsMap(args), args.version)
  }

  /**
   * Builds the current system model based on the live data from ZooKeeper
   */
  SystemModel getCurrentSystemModel(Fabric fabric)
  {
    def allInfosAndAccuracy = getAllInfosWithAccuracy(fabric)
    def agents = allInfosAndAccuracy.allInfos
    def accuracy = allInfosAndAccuracy.accuracy

    SystemModel systemModel = new SystemModel(fabric: fabric.name)

    def emptyAgents = []

    agents.values().each { agent ->
      def agentName = agent.info.agentName
      if(agent.mountPoints)
      {
        agent.mountPoints.values().each { MountPointInfo mp ->

          SystemEntry se = new SystemEntry()

          se.agent = agentName
          se.mountPoint = mp.mountPoint.toString()
          se.script = mp.data?.scriptDefinition?.scriptFactory?.location

          // all the necessary values are stored in the init parameters
          mp.data?.scriptDefinition?.initParameters?.each { k,v ->
            if(v != null)
            {
              if(k == 'metadata')
                se.metadata = LangUtils.deepClone(v)
              else
                se.initParameters[k] = v
            }
          }

          se.metadata.currentState = mp.currentState
          if(mp.transitionState)
            se.metadata.transitionState = mp.transitionState
          se.metadata.modifiedTime = mp.modifiedTime

          systemModel.addEntry(se)
        }
      }
      else
      {
        emptyAgents << agentName
      }
    }

    systemModel.metadata.accuracy = accuracy
    systemModel.metadata.emptyAgents = emptyAgents

    return systemModel
  }

  /**
   * Computes the current environment based on the live ZooKeeper data
   */
  Environment getCurrentEnvironment(Fabric fabric)
  {
    computeEnvironment(fabric, getCurrentSystemModel(fabric))
  }

  /**
   * Computes the environment based on the system
   */
  Environment computeEnvironment(Fabric fabric, SystemModel system)
  {
    def agentInfos = getAgentInfos(fabric)

    def installations = []

    system.each { SystemEntry se ->

      AgentInfo agentInfo = agentInfos[se.agent]

      if(agentInfo)
      {
        def installation = toInstallation(agentInfo.URI, se)
        if(installation)
          installations << installation
      }
    }

    def args = [:]
    args.name = 'currentSystem'
    args.installations = installations

    return new Environment(args)
  }

  Environment getAgentEnvironment(Fabric fabric, String agentName)
  {
    return doGetAgentEnvironment(fabric, agentName) { it }
  }

  Environment getAgentEnvironment(Fabric fabric, String agentName, mountPoint)
  {
    mountPoint = MountPoint.create(mountPoint.toString())
    return doGetAgentEnvironment(fabric, agentName) { mountPoints ->
      mountPoints.findAll { it.mountPoint == mountPoint}
    }
  }

  private Environment doGetAgentEnvironment(Fabric fabric, String agentName, Closure closure)
  {
    def mountPoints = getMountPointInfos(fabric, agentName)?.values()
    if(mountPoints == null)
    {
      // we differentiate between agent up and no mountpoints or agent down
      if(getAgentInfo(fabric, agentName))
        mountPoints = []
      else
        return null
    }
    mountPoints = closure(mountPoints)
    def installations = toInstallations(fabric, mountPoints)
    return new Environment(name: agentName, installations: installations)
  }

  private Installation toInstallation(URI agentURI, SystemEntry se)
  {
    def args = [:]

    args.hostname = se.agent
    args.mount = se.mountPoint
    args.uri = agentURI
    args.name = se.key
    args.gluScript = se.script
    args.state = se.metadata.currentState
    args.transitionState = se.metadata.transitionState
    args.props = [*:se.initParameters]
    def metadata = LangUtils.deepClone(se.metadata)
    metadata.remove('currentState')
    metadata.remove('transitionState')
    metadata.remove('modifiedTime')
    args.props.metadata = metadata

    if(args.state == null || availableStates.contains(args.state))
      return new Installation(args)
    else
    {
      log.warn("Ignoring ${se.key} because state [${se.metadata.currentState}] is not recognized")
      return null
    }
  }

  private def toInstallations(Fabric fabric, mountPoints)
  {
    def agentInfos = getAgentInfos(fabric)

    def installations = [:]

    if(mountPoints)
    {
      installations[MountPoint.ROOT] = null

      def list = new LinkedList(mountPoints)

      while(!list.isEmpty())
      {
        MountPointInfo mp = list.removeFirst()

        if(!installations.containsKey(mp.parent))
        {
          list.addLast(mp)
        }
        else
        {
          if(availableStates.contains(mp.currentState))
          {
            AgentInfo agentInfo = agentInfos[mp.agentName]

            if(agentInfo)
            {
              installations[mp.mountPoint] =
                new Installation(hostname: mp.agentName,
                                 mount: mp.mountPoint.path,
                                 uri: agentInfo.URI,
                                 name: mp.scriptDefinition.initParameters[InstallationDefinition.INSTALLATION_NAME],
                                 gluScript: mp.scriptDefinition.scriptFactory.location,
                                 state: mp.currentState,
                                 transitionState: mp.transitionState,
                                 props: mp.scriptDefinition.initParameters,
                                 parent: installations[mp.parent])
            }
          }
          else
          {
            log.warn("Ignoring ${mp.agentName}:${mp.mountPoint.path} because state [${mp.currentState}] is invalid")
          }
        }
      }

      installations.remove(MountPoint.ROOT)
    }

    return installations.values().toList()
  }

  private def moveToState(agent, mountPoint, toState, timeout)
  {
    def state = agent.getState(mountPoint: mountPoint)

    if(state.error)
    {
      agent.clearError(mountPoint: mountPoint)
    }

    def path = stateMachine.findShortestPath(state.currentState, toState)

    path.each { transition ->
      agent.executeAction(mountPoint: mountPoint,
                          action: transition.action)
      agent.waitForState(mountPoint: mountPoint,
                         state: transition.to,
                         timeout: timeout)
    }
  }

  private def withRemoteAgent(Fabric fabric, String agentName, Closure closure)
  {
    agentFactory.withRemoteAgent(getAgentInfo(fabric, agentName).getURI()) { Agent agent ->
      closure(agent)
    }
  }

}