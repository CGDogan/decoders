# Can be substituted with apt-get install libopenslide0
FROM cgd30/openslide:newv8

RUN apt-get -q update -y
RUN apt-get -q install -y wget

ARG TARGETARCH

RUN if [ "$TARGETARCH" = arm64 ]; then  \ 
echo aarch64 >> /platformid; \
else \
echo x64 >> /platformid; \
fi

# Set up Java
# The link is from https://github.com/graalvm/graalvm-ce-builds/releases/
# To update, please remember to download the archive on your real machine
# and unzip it and update the "mv" source directory name here
RUN wget -q "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-20.0.2/graalvm-community-jdk-20.0.2_linux-$(cat /platformid)_bin.tar.gz" -O jdk.tar.gz && tar -xzvf jdk.tar.gz > /dev/null && rm jdk.tar.gz && mv graalvm-community-openjdk-20.0.2+9.1 java_home

ENV JAVA_HOME=/java_home
ENV PATH="/java_home/bin:$PATH"

RUN mkdir bfbridge/
WORKDIR bfbridge
COPY . .

RUN javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java

RUN mkdir -p /usr/lib/java
RUN cp jar_files/* /usr/lib/java

RUN jar cv0f BfBridge.jar org/camicroscope/*.class
RUN mv BfBridge.jar /usr/lib/java

# Alternatively, we could rather have moved the class files with their folders directly
# RUN cp -r org /usr/lib/java

ENV BFBRIDGE_CLASSPATH=/usr/lib/java

WORKDIR /
