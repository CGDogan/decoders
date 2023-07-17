# Tracer

GraalVM requires the knowledge of classes that will be called through reflection for producing a library. This requires manual listing or running the tracer:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge
```

Every time the second command is run, GraalVM adds to the list of classes accessed through reflection. The goal is to not leave any access of reflection uncovered.

The correct way to do this is to take the source code of the library we're using (BioFormats) and write tests so that every code branch runs, and run them with the tracer.

## Why not Memoizer cache of Bioformats

We can use BioFormats' Memoizer which needs extra care. As of 2023, https://github.com/ome/bioformats/blob/v6.14.0/components/formats-bsd/src/loci/formats/Memoizer.java#L292-L321 saves Integer, String, class. Hence added to META-INF/reflect-config.json:

```
{
  "name": "com.esotericsoftware.kryo.serializers.DefaultSerializers$ClassSerializer",
  "allDeclaredConstructors": true
},
{
  "name": "com.esotericsoftware.kryo.serializers.DefaultSerializers$IntSerializer",
  "allDeclaredConstructors": true
},
{
  "name": "com.esotericsoftware.kryo.serializers.DefaultSerializers$StringSerializer",
  "allDeclaredConstructors": true
},
```

If other save functions are added, they should be added in the same pattern to `reflect-config.json`. The error message that prompts e.g. `com.esotericsoftware.kryo.serializers.DefaultSerializers$ClassSerializer` is:

```
ca-iip	| 17:48:13.040 [main] WARN loci.formats.Memoizer - failed to save memo file: /images/cachedir/images/.OS-1.ndpi.tiff.bfmemo
ca-iip	| java.lang.IllegalArgumentException: Unable to create serializer "com.esotericsoftware.kryo.serializers.DefaultSerializers$ClassSerializer" for class: java.lang.Class
ca-iip	|     at com.esotericsoftware.kryo.SerializerFactory$ReflectionSerializerFactory.newSerializer(SerializerFactory.java:86)
ca-iip	|     at com.esotericsoftware.kryo.SerializerFactory$ReflectionSerializerFactory.newSerializer(SerializerFactory.java:64)
ca-iip	|     at com.esotericsoftware.kryo.Kryo.getDefaultSerializer(Kryo.java:451)
ca-iip	|     at com.esotericsoftware.kryo.util.DefaultClassResolver.registerImplicit(DefaultClassResolver.java:89)
ca-iip	|     at com.esotericsoftware.kryo.Kryo.getRegistration(Kryo.java:581)
ca-iip	|     at com.esotericsoftware.kryo.Kryo.writeObject(Kryo.java:627)
ca-iip	|     at loci.formats.Memoizer$KryoDeser.saveReader(Memoizer.java:211)
ca-iip	|     at loci.formats.Memoizer.saveMemo(Memoizer.java:1004)
ca-iip	|     at loci.formats.Memoizer.setId(Memoizer.java:733)
ca-iip	|     at org.camicroscope.BFBridge.BFOpen(BFBridge.java:107)
ca-iip	| Caused by: java.lang.InstantiationException: com.esotericsoftware.kryo.serializers.DefaultSerializers$ClassSerializer
ca-iip	|     at java.base@20.0.1/java.lang.Class.newInstance(DynamicHub.java:679)
ca-iip	|     at com.esotericsoftware.kryo.SerializerFactory$ReflectionSerializerFactory.newSerializer(SerializerFactory.java:80)
ca-iip	|     ... 9 common frames omitted
ca-iip	| Caused by: java.lang.NoSuchMethodException: com.esotericsoftware.kryo.serializers.DefaultSerializers$ClassSerializer.<init>()
ca-iip	|     at java.base@20.0.1/java.lang.Class.checkMethod(DynamicHub.java:1051)
ca-iip	|     at java.base@20.0.1/java.lang.Class.getConstructor0(DynamicHub.java:1214)
ca-iip	|     at java.base@20.0.1/java.lang.Class.newInstance(DynamicHub.java:666)
ca-iip	|     ... 10 common frames omitted
ca-iip	| 17:48:13.040 [main] DEBUG loci.formats.Memoizer - start[1689529693038] time[1] tag[loci.formats.Memoizer.saveMemo]
ca-iip	| 17:48:13.040 [main] DEBUG loci.formats.Memoizer - start[1689529692508] time[531] tag[loci.formats.Memoizer.setId]
```



**Updating BioFormats**: There's likely no change in Memoizer.java, but a new FormatReader might have been added. Please run:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge
```

On a directory where there's a file that is definitely not a WSI file. This will check for each format if the file belongs to that so that each format class is added.