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
test = new com.mirantis.mk.Test()

//def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')
def target = env.TEST_TEMPEST_TARGET
def dockerImageLink = env.TEST_TEMPEST_IMAGE
// def pattern = '--regex smoke'
def pattern = '--regex designate_tempest_plugin.tests.api'
//node(docker) {
     
    stage ('Connect to salt master') {
        saltMaster = salt.connection(env.SALT_MATER_URL, env.SALT_MASTER_CREDENTIALS)
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
//}
