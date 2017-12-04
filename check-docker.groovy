/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Get ready created docker container
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - If somoke are passed image is OK
 * 
 *
 * Flow parameters:
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   OPENSTACK_API_USER_DOMAIN         OpenStack user domain
 *   OPENSTACK_API_CLIENT              Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
 *   SALT_OVERRIDES                    Override reclass model parameters
 *   STACK_DELETE                      Whether to cleanup created stack
 *   RUN_JOB_TO_TEST                    Job for launching tests
 *   STACK_TYPE                        Environment type (heat, physical, kvm)
 *   STACK_INSTALL                     Which components of the stack to install
 *   TEST_TEMPEST_CONF                 Tempest configuration file path inside container
 *   TEST_TEMPEST_TARGET               Salt target for tempest tests
 *   TEST_TEMPEST_PATTERN              Tempest tests pattern
 *   TEST_MILESTONE                    MCP version
 *   TEST_MODEL                        Reclass model of environment
 *   TEST_PASS_THRESHOLD               Persent of passed tests to consider build successful
 *   TEST_TEMPEST_IMAGE		       Docker image to test
 *
 **/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()

def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')
def build_result = 'FAILURE'
def slave_node = 'python'
def dockerImageLink= 'TEST_TEMPEST_IMAGE'

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
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: STACK_TEST],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
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
            if (use_pepper) {
                python.setupPepperVirtualenv(venv, salt_master_url, SALT_MASTER_CREDENTIALS, true)
                saltMaster = venv
            } else {
                saltMaster = salt.connection(salt_master_url, SALT_MASTER_CREDENTIALS)
            }
        }
        
        if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
            test.install_docker(saltMaster, TEST_TEMPEST_TARGET)
        }

        stage ('Check docker image and run smoke test') {

            tempest_stdout = salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                    "-v /root/:/home/tests " +
                                    "${dockerImageLink} " +
                                    "--regex smoke >> docker-tempest.log")
  
        }
    }catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
