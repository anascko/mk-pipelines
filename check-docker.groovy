/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Get ready created docker container
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - If somoke are passed image is OK and if failed what need to do ?
 * 
 *
 * Flow parameters:
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   SALT_MASTER_CREDENTIALS
 *   TEST_DOCKER_INSTALL
 *   STACK_DELETE                      Whether to cleanup created stack
 *   RUN_JOB_TO_TEST                    Job for launching tests
 *   STACK_TYPE                        Environment type (heat, physical, kvm)
 *   STACK_INSTALL                     Which components of the stack to install
 *   TEST_TEMPEST_TARGET
 *   TEST_TEMPEST_IMAGE		       Docker image to test
 *
 **/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

//def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')
def build_result = 'FAILURE'
def slave_node = 'python'
def target = env.TEST_TEMPEST_TARGET
def dockerImageLink = env.TEST_TEMPEST_IMAGE
def pattern = '--regex smoke'

node(slave_node) {
    def deployBuild
    def salt_master_url
    def stack_name
    def formula_pkg_revision = 'stable'
    def node_name = slave_node

    try {
         // Deploy MCP environment
        stage('Trigger deploy job') {
            deployBuild = build(job: RUN_JOB_TO_TEST, propagate: false, parameters: [
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: STACK_TEST],
                [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
            ])
        }
        // get salt master url
        salt_master_url = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
        common.infoMsg("Salt API is accessible via ${salt_master_url}")
        
        // Try to set stack name for stack cleanup job
        if (deployBuild.description) {
            stack_name = deployBuild.description.tokenize(' ')[0]
        }
        if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
        }

        stage ('Connect to salt master') {
            saltMaster = salt.connection(salt_master_url, env.SALT_MASTER_CREDENTIALS)
        }
        
        if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
            test.install_docker(saltMaster, TEST_TEMPEST_TARGET)
        }

        stage ('Check docker image and run smoke test') {
            salt.cmdRun(saltMaster, "${target}", "docker run --rm --net=host " +
                                    "-v /root/:/home/tests " +
                                    "${dockerImageLink} " +
                                    "${pattern} >> docker-tempest.log")
                  
        }   
    }catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
