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
# The link is from https://jdk.java.net/
# To update, please remember to download the archive on your real machine
# and unzip it and update the "mv" source directory name here
RUN wget -q "https://download.java.net/java/early_access/jdk21/33/GPL/openjdk-21-ea+33_linux-$(cat /platformid)_bin.tar.gz" -O jdk.tar.gz && tar -xzvf jdk.tar.gz > /dev/null && rm jdk.tar.gz && mv jdk-21 java_home

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

WORKDIR /
