/**
 * Launch system tests against new package.
 * Flow parameters:
 *   EXTRA_REPO                        Repository with additional packages
 *   EXTRA_REPO_PIN                    Pin string for extra repo - eg "origin hostname.local"
 *   EXTRA_REPO_PRIORITY               Repo priority
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
def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')

node {
    def cred = common.getCredentials(CREDENTIALS_ID, 'key')
    def gerritChange = gerrit.getGerritChange(cred.username, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
    def extra_repo = EXTRA_REPO
    def salt_master_url
    def deployBuildParams
    def PROJECT
    def deployBuild
    def repoChangeUrl = "http://perestroika-repo-tst.infra.mirantis.net/review/CR-35649//mcp-repos/ocata/xenial ocata main"
    
    try {

        if (common.validInputParam('GERRIT_CHANGE_NUMBER')) {
            revievNumber = "${GERRIT_CHANGE_NUMBER}"
            extra_repo = "deb [ arch=amd64 trusted=yes ]  http://perestroika-repo-tst.infra.mirantis.net/review/CR-${revievNumber}/mcp-repos/ocata/xenial  ocata main"
        }

        // Setting extra repo
        if (extra_repo) {
            // by default pin to fqdn of extra repo host
            def extra_repo_pin = EXTRA_REPO_PIN ?: "origin ${extra_repo.tokenize('/')[1]}"
            def extra_repo_priority = EXTRA_REPO_PRIORITY ?: '1200'
            def extra_repo_params = ["linux_system_repo: ${extra_repo}",
                                     "linux_system_repo_priority: ${extra_repo_priority}",
                                     "linux_system_repo_pin: ${extra_repo_pin}",]
            for (item in extra_repo_params) {
               salt_overrides_list.add(item)
            }
        }

        if (salt_overrides_list) {
            common.infoMsg("Next salt model parameters will be overriden:\n${salt_overrides_list.join('\n')}")
        }

        stage('Trigger deploy job') {
            deployBuild = build(job: STACK_DEPLOY_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: 'mcp-oscore'],
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                [$class: 'BooleanParameterValue', name: 'TEST_DOCKER_INSTALL', value: false],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
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
    } catch (Exception e) {        currentBuild.result = 'FAILURE'
        throw e
    }
}
