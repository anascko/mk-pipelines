/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Get ready created docker container
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - If somoke are passed image is OK
 * 
**/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()

//def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')

node(python) {
    def deployBuild
    def salt_master_url
    def stack_name
    def formula_pkg_revision = 'stable'
    def node_name = slave_node
    def use_pepper = true

    stage ('Connect to salt master') {
        if (use_pepper) {
            python.setupPepperVirtualenv(venv, SALT_MATER_URL, SALT_MASTER_CREDENTIALS, true)
            saltMaster = venv
        } else {
            saltMaster = salt.connection(SALT_MATER_URL, SALT_MASTER_CREDENTIALS)
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
}
