# Jenkins LTS with Docker CLI (pipeline stages run in docker agents on the host's daemon),
# plugins pre-installed and configuration-as-code — no manual setup wizard.
FROM jenkins/jenkins:lts-jdk21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends docker.io && rm -rf /var/lib/apt/lists/* \
    && usermod -aG docker jenkins
USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY jenkins.yaml /usr/share/jenkins/ref/jenkins.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Xmx512m" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/jenkins.yaml
