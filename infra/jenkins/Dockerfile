# Jenkins LTS with everything the pipeline needs installed in the image (Maven, Node 18, Python 3,
# Helm, Docker CLI) so stages run on the built-in node and Testcontainers talks to the host daemon
# through the mounted socket — no docker-in-docker agents to keep working.
FROM jenkins/jenkins:lts-jdk21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        docker.io docker-cli maven python3 python3-venv python3-pip curl ca-certificates git \
    && curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && apt-get install -y --no-install-recommends nodejs \
    && curl -fsSL https://get.helm.sh/helm-v3.16.4-linux-amd64.tar.gz | tar xz -C /tmp && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && rm -rf /var/lib/apt/lists/* /tmp/linux-amd64 \
    && usermod -aG docker jenkins \
    && git config --system --add safe.directory '*'
USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY jenkins.yaml /usr/share/jenkins/ref/jenkins.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true -Xmx512m" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/jenkins.yaml
