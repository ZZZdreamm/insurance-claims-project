// Runs on the Jenkins from `docker compose --profile ci up` (infra/jenkins): every stage executes in
// a throw-away Docker agent on the host daemon, and Testcontainers inside those agents reach the same
// daemon through the mounted socket. GitHub Actions (.github/workflows/ci.yml) runs the same steps.
pipeline {
  agent none
  options { timestamps(); timeout(time: 60, unit: 'MINUTES'); disableConcurrentBuilds() }
  environment {
    DOCKER_SOCK = '-v /var/run/docker.sock:/var/run/docker.sock'
    TESTCONTAINERS_HOST_OVERRIDE = 'host.docker.internal'   // agents are containers: containers they start must be addressed via the host
  }
  stages {
    stage('Build & test') {
      parallel {
        stage('Java services') {
          agent { docker { image 'maven:3.9-eclipse-temurin-21'; args "${DOCKER_SOCK} -v jenkins-m2:/root/.m2 --add-host=host.docker.internal:host-gateway" } }
          steps { sh 'mvn -B verify' }
          post { always { junit '**/target/surefire-reports/*.xml' } }
        }
        stage('assessment-service') {
          agent { docker { image 'python:3.12-slim' } }
          steps { dir('assessment-service') { sh 'pip install --no-cache-dir -r requirements-dev.txt && python -m pytest -q' } }
        }
        stage('adjuster-console') {
          agent { docker { image 'node:18-alpine' } }
          steps { dir('adjuster-console') { sh 'npm ci && npm run typecheck && npm run build' } }
        }
        stage('Helm') {
          agent { docker { image 'alpine/helm:3.16.4'; args '--entrypoint=' } }
          steps { sh 'helm lint deploy/helm/claims-platform && helm template claims deploy/helm/claims-platform > /dev/null' }
        }
      }
    }
    stage('Images') {
      when { branch 'main' }
      agent { docker { image 'docker:27-cli'; args "${DOCKER_SOCK}" } }
      steps {
        script {
          def tag = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'local'
          for (svc in ['claim-service', 'payout-service', 'search-service', 'assessment-service', 'adjuster-console']) {
            sh "docker build -t claims/${svc}:${tag} ./${svc}"
          }
        }
      }
    }
  }
}
