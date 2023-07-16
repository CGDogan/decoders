# Optimizing

Native-image allows initializing classes at runtime.

```
native-image --initialize-at-build-time <other args>
```

but this prints errors such as:

```
Error: Class initialization of sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher failed. Use the option --initialize-at-run-time=sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher to explicitly request delayed initialization of this class.
```

There are other problematic sun.awt classes as well so do:

```
native-image --initialize-at-build-time --initialize-at-run-time=sun.awt --initialize-at-run-time=...other failing classes/packages... <other args>
```

Please note that as of now although native-image prints errors, it (wrongly) returns with a success code. We know that compilation failed because no .so files were produced hence the next command `RUN cp -t /usr/local/lib *.so` fails

Now, native-image says that the classes whose optimization we wanted to skip were nevertheless initialized due to other optimized classes requiring them:

```
sun.awt.SunToolkit the class was requested to be initialized at run time (from command line with 'sun.awt'). To see why sun.awt.SunToolkit got initialized use --trace-class-initialization=sun.awt.SunToolkit
sun.awt.util.PerformanceLogger the class was requested to be initialized at run time (from command line with 'sun.awt'). To see why sun.awt.util.PerformanceLogger got initialized use --trace-class-initialization=sun.awt.util.PerformanceLogger
sun.awt.UNIXToolkit the class was requested to be initialized at run time (from command line with 'sun.awt'). To see why sun.awt.UNIXToolkit got initialized use --trace-class-initialization=sun.awt.UNIXToolkit
sun.awt.X11.XToolkit the class was requested to be initialized at run time (from command line with 'sun.awt'). To see why sun.awt.X11.XToolkit got initialized use --trace-class-initialization=sun.awt.X11.XToolkit
To see how the classes got initialized, use --trace-class-initialization=sun.awt.X11GraphicsEnvironment,sun.awt.AppContext,sun.awt.SunToolkit,sun.awt.util.PerformanceLogger,sun.awt.UNIXToolkit,sun.awt.X11.XToolkit
29.72 Error: Use -H:+ReportExceptionStackTraces to print stacktrace of underlying exception
29.72 ------------------------------------------------------------------------------------------------------------------------
29.72                        6.6s (22.4% of total time) in 126 GCs | Peak RSS: 2.05GB | CPU load: 3.68
29.72 ========================================================================================================================
29.72 Finished generating 'libbfbridge' in 29.1s.
```

The summary line is the important line:

```
--trace-class-initialization=sun.awt.X11GraphicsEnvironment,sun.awt.AppContext,sun.awt.SunToolkit,sun.awt.util.PerformanceLogger,sun.awt.UNIXToolkit,sun.awt.X11.XToolkit
```

Adding this to build args, we get lines such as:

```
#16 29.20 sun.awt.SunToolkit the class was requested to be initialized at run time (from command line with 'sun.awt'). javax.imageio.ImageIO caused initialization of this class with the following trace: 
#16 29.20 	at sun.awt.SunToolkit.<clinit>(SunToolkit.java:122)
#16 29.20 	at sun.awt.AppContext$2.run(AppContext.java:273)
#16 29.20 	at sun.awt.AppContext$2.run(AppContext.java:262)
#16 29.20 	at java.security.AccessController.executePrivileged(AccessController.java:778)
#16 29.20 	at java.security.AccessController.doPrivileged(AccessController.java:319)
#16 29.20 	at sun.awt.AppContext.initMainAppContext(AppContext.java:262)
#16 29.20 	at sun.awt.AppContext$3.run(AppContext.java:315)
#16 29.20 	at sun.awt.AppContext$3.run(AppContext.java:298)
#16 29.20 	at java.security.AccessController.executePrivileged(AccessController.java:778)
#16 29.20 	at java.security.AccessController.doPrivileged(AccessController.java:319)
#16 29.20 	at sun.awt.AppContext.getAppContext(AppContext.java:297)
#16 29.20 	at javax.imageio.spi.IIORegistry.getDefaultInstance(IIORegistry.java:123)
#16 29.20 	at javax.imageio.ImageIO.<clinit>(ImageIO.java:64)
#16 29.20 
#16 29.20 sun.awt.X11.XToolkit the class was requested to be initialized at run time (from command line with 'sun.awt'). java.awt.event.MouseEvent caused initialization of this class with the following trace: 
#16 29.20 	at sun.awt.X11.XToolkit.<clinit>(XToolkit.java:156)
#16 29.20 	at sun.awt.PlatformGraphicsInfo.createToolkit(PlatformGraphicsInfo.java:41)
#16 29.20 	at java.awt.Toolkit.getDefaultToolkit(Toolkit.java:595)
#16 29.20 	at java.awt.event.MouseEvent.<clinit>(MouseEvent.java:402)
```

The last lines are important: `java.awt.event.MouseEvent`, `javax.imageio.ImageIO`. We can choose to be more general and skip `java.awt` and `javax.imageio`. Adding these to arguments and removing the trace argument, we get more "class X not optimized was required, run trace", so we again add the summarized trace flag, find what root classes required them, remove the trace flag add them as no-optimize flags, repeat.

Updating JDK or updating BioFormats and its libraries might require one or two iterations more