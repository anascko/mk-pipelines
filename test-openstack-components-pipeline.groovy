/**
 * Launch system tests against new package.
 * Flow parameters:
 *   CREDENTIALS_ID
 *   EXTRA_FORMULAS
 *   FORMULAS_REVISION
 *   FORMULAS_SOURCE
 *   SALT_OPTS
 *   STACK_DEPLOY_JOB
 *   STACK_TEST_JOB
 *   TEST_TEMPEST_TARGET
 *   TEST_TEMPEST_PATTERN
**/
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()

node {
    def cred = common.getCredentials(CREDENTIALS_ID, 'key')
    def gerritChange = gerrit.getGerritChange(cred.username, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
    def salt_master_url
    def deployBuildParams
    def PROJECT
    def deployBuild
 
    stage('Trigger deploy job') {
        deployBuild = build(job: STACK_DEPLOY_JOB, parameters: [
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: 'mcp-oscore'],
            [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
            [$class: 'BooleanParameterValue', name: 'TEST_DOCKER_INSTALL', value: false]
        ])
    }
    
    // get salt master url
        deployBuildParams = deployBuild.description.tokenize( ' ' )
        salt_master_url = "http://${deployBuildParams[1]}:6969"
        common.infoMsg("Salt API is accessible via ${salt_master_url}")
    
    // Run smoke tests
    stage('Run Smoke tests') {
        build(job: STACK_TEST_JOB, parameters: [
            [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
            [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
            [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: 'set=smoke'],
            [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
            [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
            [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
            [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
        ])
    } 
}
