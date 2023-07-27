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

RUN wget -q "https://download.oracle.com/graalvm/20/latest/graalvm-jdk-20_linux-$(cat /platformid)_bin.tar.gz" -O graal.tar.gz && tar -xzvf graal.tar.gz > /dev/null && rm graal.tar.gz
ENV JAVA_HOME=/graalvm-jdk-20.0.2+9.1
ENV PATH="/graalvm-jdk-20.0.2+9.1/bin:$PATH"

RUN mkdir bfbridge/
COPY . bfbridge
WORKDIR bfbridge

RUN javac Ab.java
RUN native-image Ab

#RUN tar -xzvf javahome.tar.gz > /dev/null
#RUN mkdir /graalvm-community-openjdk-20.0.1+9.1
#RUN mv -t /graalvm-community-openjdk-20.0.1+9.1 labsjdk-ce-21-jvmci-23.1-b09/graal/sdk/mxbuild/linux-aarch64/GRAALVM_COMMUNITY_JAVA21/graalvm-community-openjdk-21+28.1/* 
#--initialize-at-run-time=sun.awt,javax.imageio.ImageIO,java.awt,com.sun.imageio,sun.java2d,sun.font,loci.formats.gui.AWTImageTools,ome.specification.XMLMockObjects,ome.codecs.gui.AWTImageTools,javax.imageio.ImageTypeSpecifier --initialize-at-build-time
RUN javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
RUN native-image -Djava.awt.headless=true -cp ".:jar_files/*" --shared -H:Name=libbfbridge   org.camicroscope.BFBridge
RUN ls
RUN cp -t /usr/local/lib *.so
RUN cp -t /usr/local/include *.h
WORKDIR /

