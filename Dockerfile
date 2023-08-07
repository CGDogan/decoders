# Multi-stage Docker build to minimize the final Java library directory size
# The first stage downloads the JDK and compiles the Java class
# The second stage does the rest

FROM ubuntu:lunar AS builder

ARG TARGETARCH
RUN if [ "$TARGETARCH" = arm64 ]; then  \ 
echo aarch64 >> /platformid; \
else \
echo x64 >> /platformid; \
fi

RUN apt-get -q update -y && apt-get -q install -y wget

# Set up Java
# The link is from https://jdk.java.net/
# To update, please remember to download the archive on your real machine
# and unzip it and update the "mv" source directory name here
RUN wget -q "https://download.java.net/java/early_access/jdk21/33/GPL/openjdk-21-ea+33_linux-$(cat /platformid)_bin.tar.gz" -O jdk.tar.gz && tar -xzvf jdk.tar.gz > /dev/null && rm jdk.tar.gz && mv jdk-21 /java_home

COPY jar_files/ /jar_files/
COPY org/camicroscope/ /org/camicroscope/

RUN /java_home/bin/javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java


# Can be substituted with apt-get install libopenslide0
FROM cgd30/openslide:newv8

RUN apt-get -q update -y
RUN apt-get -q install -y wget

# this will contain the Java Development Kit or the Java Runtime Environment
ENV JAVA_HOME=/java_home
ENV PATH="/java_home/bin:$PATH"

COPY --from=builder ./platformid /

# Please see the notice above about updating the folder name
# The link is from https://adoptium.net/temurin/archive/
# Please choose Package Type: JRE and newest version
# You can then use the Network Tab in developer tools to see GitHub download
# link. Or you can browse the repository manually
# https://github.com/adoptium/temurin20-binaries/releases for the "jre linux" package
# under a release. Make sure not to choose "debugimage" or "testimage" or "sbom"
# etc. but something like "OpenJDK20U-jre_aarch64_linux_hotspot_20.0.2_9.tar.gz"
RUN wget -q "https://github.com/adoptium/temurin20-binaries/releases/download/jdk-20.0.2%2B9/OpenJDK20U-jre_$(cat /platformid)_linux_hotspot_20.0.2_9.tar.gz" -O jre.tar.gz && tar -xzvf jre.tar.gz > /dev/null && rm jre.tar.gz && mv jdk-20.0.2+9-jre java_home

RUN mkdir bfbridge/
WORKDIR bfbridge

COPY --from=builder /jar_files ./jar_files
COPY --from=builder /org/camicroscope/*.class ./org/camicroscope

RUN mkdir -p /usr/lib/java

# Dependencies
RUN cp jar_files/* /usr/lib/java

# Move our class files with their folders
RUN cp -r org /usr/lib/java

# Alternatively, we could have packed our classes to a .jar
# RUN jar cv0f BfBridge.jar org/camicroscope/*.class
# RUN mv BfBridge.jar /usr/lib/java

WORKDIR /
