# Tracer

GraalVM requires the knowledge of classes that will be called through reflection for producing a library. This requires manual listing or running the tracer:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge
```

Every time the second command is run, GraalVM adds to the list of classes accessed through reflection. The goal is to not leave any access of reflection uncovered.

The correct way to do this is to take the source code of the library we're using (BioFormats) and write tests so that every code branch runs, and run them with the tracer.

**Updating BioFormats**: There's likely no change in Memoizer.java, but a new FormatReader might have been added. Please run:

```
javac -cp ".:jar_files/*" org/camicroscope/BFBridge.java
graalvm/java -cp ".:jar_files/*" -agentlib:native-image-agent=config-merge-dir=META-INF/native-image org.camicroscope.BFBridge
```

On a directory where there's a file that is definitely not a WSI file. This will check for each format if the file belongs to that so that each format class is added.

## Bioformats memoizer.java

Uses kryo to save class bytecode, which is not supported by GraalVM, because native-image compiles bytecode to machine code. The closest possibility is hardcoding [exact classes seen by the tracer](https://www.graalvm.org/22.1/reference-manual/native-image/ExperimentalAgentOptions/), which is very far.
