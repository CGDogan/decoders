# Can be substituted with apt-get install libopenslide0
FROM cgd30/openslide:newv7

RUN apt-get -q update -y
RUN apt-get -q install -y wget

ARG TARGETARCH

RUN if [ "$TARGETARCH" = arm64 ]; then  \ 
echo aarch64 >> /platformid; \
else \
echo x64 >> /platformid; \
fi

RUN cat /platformid

#removeme with cgd30 openslide update
WORKDIR /

RUN wget -q "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-20.0.1/graalvm-community-jdk-20.0.1_linux-$(cat /platformid)_bin.tar.gz" -O graal.tar.gz && tar -xzvf graal.tar.gz > /dev/null && rm graal.tar.gz
ENV JAVA_HOME=/graalvm-community-openjdk-20.0.1+9.1
ENV PATH="/graalvm-community-openjdk-20.0.1+9.1/bin:$PATH"

RUN mkdir bfbridge/
COPY . bfbridge
WORKDIR bfbridge
#RUN ls /graalvm-community-openjdk-20.0.1+9.1
RUN javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
RUN native-image -cp ".:jar_files/*" --shared -H:Name=libbfbridge org.camicroscope.BFBridge
RUN cp -t /usr/local/lib *.so
RUN cp -t /usr/local/include *.h
WORKDIR /

