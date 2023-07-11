
## Maven

### From scratch

Visit [Using bio-formats as a Java library](https://bio-formats.readthedocs.io/en/latest/developers/java-library.html#bio-formats-as-a-java-library)
which currently lists formats-gpl.jar as the main jar; `ome-common.jar` and `ome-xml.jar` and SLF4J, which is `slf4j-api` ([not than version 2 currently](https://bio-formats.readthedocs.io/en/latest/developers/logging.html#logging-frameworks)) and `slf4j-simple` which is normally taken from the classpath during runtime (but we would like to use GraalVM so include early).

Visit https://central.sonatype.com to find these

Currently these are:

- https://central.sonatype.com/artifact/org.openmicroscopy/ome-xml/6.3.3
- https://central.sonatype.com/artifact/org.openmicroscopy/ome-common/6.0.16
- https://central.sonatype.com/artifact/org.slf4j/slf4j-simple/1.7.36
- https://central.sonatype.com/artifact/org.slf4j/slf4j-api/1.7.36

But we also need:
- https://central.sonatype.com/artifact/org.openmicroscopy/ome-codecs/0.4.5

so I copy the Maven snippets back-to-back:

```
<dependency>
    <groupId>org.openmicroscopy</groupId>
    <artifactId>ome-xml</artifactId>
    <version>6.3.3</version>
</dependency>
<dependency>
    <groupId>org.openmicroscopy</groupId>
    <artifactId>ome-common</artifactId>
    <version>6.0.16</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>1.7.36</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.7.36</version>
</dependency>
<dependency>
    <groupId>org.openmicroscopy</groupId>
    <artifactId>ome-codecs</artifactId>
    <version>0.4.5</version>
</dependency>
```

and entering this to https://jar-download.com/online-maven-download-tool.php and downloading the project allows us to get the jars as well as the dependencies.

We also need `formats-gpl.jar`, `formats-bsd.jar`, `formats-api.jar`, `turbojpeg.jar` from https://downloads.openmicroscopy.org/bio-formats/latest/artifacts/

But we also need `org.scijava.nativelib.NativeLibraryUtil`, which is the native-lib-loader-2.4.0.jar from https://jar-download.com/artifacts/org.scijava/native-lib-loader/2.4.0

But instead of this, we could have used 130% more space and used bioformats_package.jar from https://downloads.openmicroscopy.org/bio-formats/latest/artifacts/

I added this jar nevertheless. Hence I sometimes remove `slf4j-simple.jar` against loggers competing.

I also added bio-formats-tools.jar from artifacts. This is now required for generating subresolutions. If you want to run this, you can do `java -cp ".:jar_files/*"  loci.formats.tools.ImageConverter` or with ...ImageInfo

No, I removed bio-formats-tools.jar now because the package already contains it and the same command works.

## Building

GraalVM must be a recent version. Do java -jar to see if 17.0.7+ or 20.0.2+. Otherwise you may get:

```
Exception in thread "main" java.lang.UnsatisfiedLinkError: no awt in java.library.path
	at org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk.NativeLibrarySupport.loadLibraryRelative(NativeLibrarySupport.java:136)
	at java.base@20.0.1/java.lang.ClassLoader.loadLibrary(ClassLoader.java:50)
	at java.base@20.0.1/java.lang.Runtime.loadLibrary0(Runtime.java:880)
	at java.base@20.0.1/java.lang.System.loadLibrary(System.java:2051)
	at java.desktop@20.0.1/sun.java2d.Disposer$1.run(Disposer.java:68)
	at java.desktop@20.0.1/sun.java2d.Disposer$1.run(Disposer.java:66)
	at java.base@20.0.1/java.security.AccessController.executePrivileged(AccessController.java:171)
```

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
java -cp ".:jar_files/*" org/camicroscope/BFBridge
```

or run only:

```
java -cp ".:jar_files/*" org/camicroscope/BFBridge.java
```

for native-image:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
native-image -cp ".:jar_files/*" --shared -H:Name=bfbridge org.camicroscope.BFBridge
```

## Optimization

https://medium.com/graalvm/introducing-the-tracing-agent-simplifying-graalvm-native-image-configuration-c3b56c486271

First created with `graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-output-dir=META-INF/native-image org.camicroscope.BFBridge`

Then used with `graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge`

## Loading resources

https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Resources/

I got:

```
[main] WARN loci.formats.ClassList - Could not find readers.txt
```

and this is one of the files bundled inside one of bioformats jars. These are accessed through getResourceAsStream() and searching for this in bioformats repo reveals these.

So I edited resource-config.json and added -H:Log=registerResource:3 to compilation

I took the inclusive bioformats_package.jar and decompressed it.

Also, this is OK:

```
Warning: Could not resolve class loci.formats.in.ScreenReader for reflection configuration. Reason: java.lang.ClassNotFoundException: loci.formats.in.ScreenReader.
Warning: Could not resolve class loci.formats.in.SlideBook6Reader for reflection configuration. Reason: java.lang.ClassNotFoundException: loci.formats.in.SlideBook6Reader.
Warning: Could not resolve class loci.formats.in.URLReader for reflection configuration. Reason: java.lang.ClassNotFoundException: loci.formats.in.URLReader.
Warning: Could not resolve class loci.formats.in.ZarrReader for reflection configuration. Reason: java.lang.ClassNotFoundException: loci.formats.in.ZarrReader.
```

These are optional classes we won't need.

But if you get:

```
Exception in thread "main" java.lang.UnsatisfiedLinkError: no awt in java.library.path
```

native-image already said: AWT:  Use the tracing agent to collect metadata for AWT.

What to do?

Is it https://github.com/graalvm/mandrel/issues/487 referring to fixed in JDK 21?

very similar https://github.com/oracle/graal/issues/6244