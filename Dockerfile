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

RUN cat /platformid

RUN wget -q "https://download.java.net/java/early_access/jdk21/33/GPL/openjdk-21-ea+33_linux-$(cat /platformid)_bin.tar.gz" -O jdk.tar.gz && tar -xzvf jdk.tar.gz > /dev/null && rm jdk.tar.gz
ENV JAVA_HOME=/jdk-21
ENV PATH="/jdk-21/bin:$PATH"

RUN mkdir bfbridge/
COPY . bfbridge
WORKDIR bfbridge

RUN javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
RUN mkdir -p /usr/lib/java
RUN cp -r org /usr/lib/java
RUN cp jar_files/* /usr/lib/java
RUN jar cvf BfBridge.jar org/camicroscope/*.class
RUN mv BfBridge.jar /usr/lib/java
WORKDIR /

