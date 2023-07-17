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

RUN wget -q "https://github.com/graalvm/graalvm-ce-dev-builds/releases/download/23.1.0-dev-20230713_2040/graalvm-community-java20-linux-$(cat /platformid)-dev.tar.gz" -O graal.tar.gz && tar -xzvf graal.tar.gz > /dev/null && rm graal.tar.gz
ENV JAVA_HOME=/graalvm-community-openjdk-20.0.1+9.1
ENV PATH="/graalvm-community-openjdk-20.0.1+9.1/bin:$PATH"

RUN mkdir bfbridge/
COPY . bfbridge
WORKDIR bfbridge

#RUN tar -xzvf javahome.tar.gz > /dev/null
#RUN mkdir /graalvm-community-openjdk-20.0.1+9.1
#RUN mv -t /graalvm-community-openjdk-20.0.1+9.1 labsjdk-ce-21-jvmci-23.1-b09/graal/sdk/mxbuild/linux-aarch64/GRAALVM_COMMUNITY_JAVA21/graalvm-community-openjdk-21+28.1/* 
RUN ls /graalvm-community-openjdk-20.0.1+9.1

RUN javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
#RUN java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge
#RUN native-image -cp ".:jar_files/*" -H:Name=libbfbridge  -O0 org.camicroscope.BFBridge
#RUN ./libbfbridge
#RUN ls
#RUN cp -t /usr/local/lib *.so
#RUN cp -t /usr/local/include *.h
CMD bash
#WORKDIR /

