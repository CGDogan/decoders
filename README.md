# caMicroscope/Decoders

WSI file processor backends for caMicroscope. Uses OpenSlide and, through GraalVM, BioFormats. Intended to be used via Docker.

Except in a Docker VM, macOS not supported due to [AWT issue on GraalVM](https://github.com/oracle/graal/issues/4124)

Alternatively, build/install openslide as usual, clone this repository and run:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
native-image -cp ".:jar_files/*" --shared -H:Name=libbfbridge org.camicroscope.BFBridge
cp -t /usr/local/lib *.so
cp -t /usr/local/include *.h
```

this will work given that you did:

```
export JAVA_HOME=/path/to/downloaded/graalvm/home
export PATH="/path/to/downloaded/graalvm/home/bin:$PATH"
```

