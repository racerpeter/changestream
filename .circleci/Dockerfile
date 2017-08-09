FROM openjdk:8-jre

ENV SCALA_VERSION 2.11.11
ENV SBT_VERSION 0.13.16

# Scala expects this file
RUN touch /usr/lib/jvm/java-8-openjdk-amd64/release

# Install Scala
## Piping curl directly in tar
RUN \
  curl -fsL https://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /root/ && \
  echo >> /root/.bashrc && \
  echo 'export PATH=~/scala-$SCALA_VERSION/bin:$PATH' >> /root/.bashrc

# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb https://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb && \
  rm sbt-$SBT_VERSION.deb && \
  apt-get update && \
  apt-get install --no-install-recommends -y --force-yes sbt && \
  sbt sbtVersion && \
  apt-get clean && rm -rf /var/lib/apt/lists/*

# Install git
RUN \
  apt-get update && \
  apt-get install --no-install-recommends -y --force-yes git ssh && \
  apt-get clean && rm -rf /var/lib/apt/lists/*

# Define working directory
WORKDIR /root
