#!groovy

// Guess username for acessing git repo
String git_user=''
if (env.GERRIT_USER) {
    git_user = "${env.GERRIT_USER}@"
} else if (env.GIT_CREDS_ID) {
    def mkCommon = new com.mirantis.mk.Common()
    def cred = mkCommon.getCredentials(env.GIT_CREDS_ID, 'key')
    git_user = "${cred.username}@"
}

// Use gerrit parameters if set with fallback to job param
String git_repo_url = env.GERRIT_HOST ? "${env.GERRIT_SCHEME}://${git_user}${env.GERRIT_HOST}:${env.GERRIT_PORT}/${env.GERRIT_PROJECT}" : env.GIT_URL
String git_ref = env.GERRIT_REFSPEC ?: env.GIT_REF
String git_credentials_id = env.GIT_CREDS_ID

String docker_registry = env.DOCKER_REGISTRY
String docker_context = env.DOCKER_CONTEXT ?: '.'

String docker_image_name = env.IMAGE_NAME
String docker_image_tag = env.IMAGE_TAG ?: 'latest'

String local_image_name = docker_image_name.split('/')[-1]

// Variable to store image oject
def built_image

String docker_dev_repo = 'docker-dev-local'

artifactory = new com.mirantis.mcp.MCPArtifactory()
artifactoryServer = Artifactory.server('mcp-ci')

// Set current build description
if (env.GERRIT_CHANGE_URL) {
    currentBuild.description = """
    <p>
      Triggered by change: <a href="${env.GERRIT_CHANGE_URL}">${env.GERRIT_CHANGE_NUMBER},${env.GERRIT_PATCHSET_NUMBER}</a><br/>
      Project: <b>${env.GERRIT_PROJECT}</b><br/>
      Branch: <b>${env.GERRIT_BRANCH}</b><br/>
      Subject: <b>${env.GERRIT_CHANGE_SUBJECT}</b><br/>
    </p>
    """
} else {
    currentBuild.description = """
    <p>
      Triggered manually<br/>
      Git repository URL: <b>${git_repo_url}</b><br/>
      Git revision: <b>${git_ref}</b><br/>
    </p>
    """
}

node('builder') {

    stage('SCM checkout') {
        echo "Checking out git repository from ${git_repo_url} @ ${git_ref}"

        checkout([
            $class: 'GitSCM',
            branches: [
                [name: 'FETCH_HEAD'],
            ],
            userRemoteConfigs: [
                [url: git_repo_url, refspec: git_ref, credentialsId: git_credentials_id],
            ],
        ])
    }

    // Build image
    stage('Build ' + local_image_name) {
        def docker_args = [
            '--pull',
            '--no-cache',
            docker_context,
        ]
        built_image = docker.build(
            ( docker_registry ? "${docker_registry}/" : '' ) + "${docker_image_name}:${docker_image_tag}",
            docker_args.join(' ')
        )
    }

    // Push image to registry
    if (docker_registry) {
        stage('Push ' + local_image_name) {
            artifactory.uploadImageToArtifactory(
                artifactoryServer,
                docker_registry,
                docker_image_name,
                docker_image_tag,
                docker_dev_repo
            )
        }
    }
}
