package org.camicroscope;

import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.FormatTools; // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/FormatTools.html
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.services.JPEGTurboServiceImpl;
import ome.units.UNITS;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import static org.graalvm.nativeimage.c.type.CTypeConversion.toCString; // Watch out for memory leaks
import static org.graalvm.nativeimage.c.type.CTypeConversion.toCBytes; // Likewise
import static org.graalvm.nativeimage.c.type.CTypeConversion.toCBoolean;
import static org.graalvm.nativeimage.c.type.CTypeConversion.toJavaString;

import loci.formats.tools.ImageConverter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.WordFactory;

//import org.camicroscope.TJUnitTest;

// https://bio-formats.readthedocs.io/en/v6.14.0/developers/file-reader.html#reading-files
// There are other utilities as well

// How does GraalVM isolates and threads work?
// https://medium.com/graalvm/isolates-and-compressed-references-more-flexible-and-efficient-memory-management-for-graalvm-a044cc50b67e

import loci.common.RandomAccessInputStream;

public class BFBridge {
    private static ImageReader reader = null; // see optimizing.md
    private static IMetadata metadata = MetadataTools.createOMEXMLMetadata();
    /*
     * static {
     * load .so files compiled along
     * alternatively, for our Docker:
     * System.setProperty("java.library.path", "/bfbridge/");
     * System.setProperty("java.library.path", "/usr/local/lib/");
     * This can be (should be? for graal) moved out of a static block
     * }
     */

    // A more efficient way to receive strings from C exists:
    // https://github.com/kirillp/graalSamples/tree/master/simpleApp
    // Pass the allocator to java
    private static CCharPointerHolder lastError = toCBytes(null);
    // private static Runnable lastErrorFreer = () -> {};

    // See the file
    // https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/handles/PrimitiveArrayView.java
    // currently uses createForReading and hence unsuitable for communication
    // from C
    private static byte[] communicationBuffer = new byte[10000000];

    @CEntryPoint(name = "bf_test")
    // see if this library works. Run this after BFClearCommunicationBuffer if not
    // fresh. The last check should be done at C side:
    // call bf_get_communication_buffer before and after this method
    // and compare
    static byte BFTest(IsolateThread t) {
        try {
            // System.setProperty("java.library.path", "/usr/local/lib/");
            // System.setProperty("java.library.path", "/bfbridge/");

            System.out.println("java.library.path:");
            System.out.println(System.getProperty("java.library.path"));
            System.out.println("See if JNI works...");
            new JPEGTurboServiceImpl();
            new org.libjpegturbo.turbojpeg.TJDecompressor();
            System.out.println("Yes");
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }

        // This turned out to be accessible only internally
        /*
         * try {
         * System.out.println("See if saving to buffer doesn't require a copy...");
         * //
         * https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.
         * core/src/com/oracle/svm/core/handles/PrimitiveArrayView.java
         * if (new PrimitiveArrayView.createForReading(new byte[10]).isCopy()) {
         * System.out.println("Correct");
         * } else {
         * throw new Exception();
         * }
         * } catch (Exception e) {
         * System.out.println("Expect memory leaks!");
         * }
         */

        System.out.println("See if buffer updates...");
        for (int i = 0; i < 10; i++) {
            communicationBuffer[i] = (byte) i;
        }
        // Verify this in C
        // If not, we need to call bf_get_communication_buffer from C
        // whenever we need to access its value
        // I think this happens iff "Expect memory leaks" is printed above
        return 0;
    }

    @CEntryPoint(name = "bf_initialize")
    // Does nothing if not initialized
    // Won't reset, use BFClearCommunicationBuffer and BFClose for that
    static byte BFInitialize(IsolateThread t) {
        try {
            if (reader == null) {
                reader = new ImageReader();
                // Use the easier resolution API
                reader.setFlattenedResolutions(false);
                reader.setMetadataStore(metadata);
            }
            // Save file-specific metadata as well?
            // metadata.setOriginalMetadataPopulated(true);
            return toCBoolean(true);
        } catch (Exception e) {
            saveError(e.toString());

            return toCBoolean(false);
        }
    }

    @CEntryPoint(name = "bf_reset")
    // Destroy the library, make it unusable
    static void BFReset(IsolateThread t) {
        close();

        try {
            lastError.close();
        } catch (Exception e) {
        }

        lastError = toCBytes(null);

        try {
            toCBytes(communicationBuffer).close();
        } catch (Exception e) {
        }

        communicationBuffer = null;
    }

    @CEntryPoint(name = "bf_get_error")
    static CCharPointer BFGetError(IsolateThread t) {
        return lastError.get();
    }

    @CEntryPoint(name = "bf_get_communication_buffer")
    // Memory for data written by Java
    // Used by BFOpenBytes and BFGetUsedFiles
    static CCharPointer BFGetCommunicationBuffer(IsolateThread t) {
        // This does not copy
        // https://github.com/oracle/graal/blob/492c6016c5d9233be5de2dd9502cc81f746fc8e7/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/c/CTypeConversionSupportImpl.java#L206
        return toCBytes(communicationBuffer).get();
    }

    @CEntryPoint(name = "bf_clear_communication_buffer")
    // If a JVM on this library was to be shared between executables
    // They would need to clear the buffer before leaving if data can be sensitive
    // Our iipsrv manages access to the buffer on its own without sharing
    // and already has access to files so this is not really useful.
    static void BFClearCommunicationBuffer(IsolateThread t) {
        for (int i = 0; i < communicationBuffer.length; i++) {
            communicationBuffer[i] = 0;
        }
    }

    @CEntryPoint(name = "bf_is_compatible")
    // Please note: this closes the previous file
    static byte BFIsCompatible(IsolateThread t, final CCharPointer filePath) {
        try {
            // If we didn't have this line, I would change
            // "private static ImageReader reader" to
            // "private static IFormatReader reader"
            return toCBoolean(reader.getReader(toJavaString(filePath)) != null);
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        } finally {
            close();
        }
    }

    /*
     * bf_is_compatible_disregarding_filename:
     * I think not needed?
     * Loop over IFormatReaders
     * use IFormatreader.isthistype with stream type
     * 
     * 
     * List<IFormatReader> potentialReaders = new ArrayList<IFormatReader>();
     * for (int i=0; i<readers.length; i++) {
     * if (isThisType(readers[i], stream)) potentialReaders.add(readers[i]);
     * }
     * IFormatReader[] readers = new IFormatReader[potentialReaders.size()];
     * potentialReaders.toArray(readers);
     * return readers;
     */

    @CEntryPoint(name = "bf_open")
    // Do not open another file without closing current
    static byte BFOpen(IsolateThread t, final CCharPointer filePath) {
        try {
            // TODO DEBUG line
            System.out.println("Opening file: " + toJavaString(filePath));
            reader.setId(toJavaString(filePath));
            // reader.setMetadataStore(metadata);
            return toCBoolean(true);
        } catch (Exception e) {
            saveError(e.toString());
            close();
            return toCBoolean(false);
        }
    }

    // If expected to be the single file, or always true for single-file formats
    @CEntryPoint(name = "bf_is_single_file")
    static byte BFIsSingleFile(IsolateThread t, CCharPointer filePath) {
        try {
            return toCBoolean(reader.isSingleFile(toJavaString(filePath)));
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        } finally {
            close();
        }
    }

    @CEntryPoint(name = "bf_get_used_files")
    // Lists null-separated filenames. Returns bytes written including the last null
    static int BFGetUsedFiles(IsolateThread t) {
        try {
            String[] files = reader.getUsedFiles();
            int charI = 0;
            for (String file : files) {
                byte[] characters = file.getBytes();
                if (characters.length + 2 > communicationBuffer.length) {
                    saveError("Too long");
                    return -2;
                }
                for (int i = 0; i < characters.length; i++) {
                    communicationBuffer[charI++] = characters[i];
                }
                communicationBuffer[charI++] = 0;
            }
            return charI;
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_close")
    static byte BFClose(IsolateThread t, int fileOnly) {
        try {
            reader.close(fileOnly != 0);
            return toCBoolean(true);
        } catch (Exception e) {
            saveError(e.toString());
            return toCBoolean(false);
        }
    }

    @CEntryPoint(name = "bf_get_resolution_count")
    static int BFGetResolutionCount(IsolateThread t) {
        try {
            // In resolution mode, each of series has a number of resolutions
            // WSI pyramids have multiple and others have one
            // This method returns resolution counts for the current series
            return reader.getResolutionCount();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_set_current_resolution")
    static byte BFSetCurrentResolution(IsolateThread t, int resIndex) {
        try {
            // Precondition: The caller must check that at least 0 and less than
            // resolutionCount
            reader.setResolution(resIndex);
            return toCBoolean(true);
        } catch (Exception e) {
            saveError(e.toString());
            return toCBoolean(false);
        }
    }

    @CEntryPoint(name = "bf_get_size_x")
    static int BFGetSizeX(IsolateThread t) {
        try {
            // For current resolution
            return reader.getSizeX();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_size_y")
    static int BFGetSizeY(IsolateThread t) {
        try {
            return reader.getSizeY();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_size_z")
    static int BFGetSizeZ(IsolateThread t) {
        try {
            return reader.getSizeZ();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_size_t")
    static int BFGetSizeT(IsolateThread t) {
        try {
            return reader.getSizeT();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_size_c")
    static int BFGetSizeC(IsolateThread t) {
        try {
            return reader.getSizeC();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_effective_size_c")
    static int BFGetEffectiveSizeC(IsolateThread t) {
        try {
            return reader.getEffectiveSizeC();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_optimal_tile_width")
    static int BFGetOptimalTileWidth(IsolateThread t) {
        try {
            return reader.getOptimalTileWidth();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_optimal_tile_height")
    static int BFGetOptimalTİleHeight(IsolateThread t) {
        try {
            return reader.getOptimalTileHeight();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_format")
    static CCharPointer BFGetFormat(IsolateThread t) {
        try {
            return toCString(reader.getFormat()).get();
        } catch (Exception e) {
            saveError(e.toString());
            return WordFactory.nullPointer();
        }
    }

    @CEntryPoint(name = "bf_get_pixel_type")
    static int BFGetPixelType(IsolateThread t) {
        try {
            return reader.getPixelType();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_bits_per_pixel")
    // The name is misleading
    // Actually this gives bits per pixel per channel!
    // openBytes documentation makes this clear
    // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/ImageReader.html#openBytes-int-byte:A-
    static int BFGetBitsPerPixel(IsolateThread t) {
        // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/IFormatReader.html#getPixelType--
        // https://github.com/ome/bioformats/blob/9cb6cfaaa5361bcc4ed9f9841f2a4caa29aad6c7/components/formats-api/src/loci/formats/FormatTools.java#L96
        try {
            return reader.getBitsPerPixel();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_bytes_per_pixel")
    static int BFGetBytesPerPixel(IsolateThread t) {
        try {
            return FormatTools.getBytesPerPixel(reader.getPixelType());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_rgb_channel_count")
    static int BFGetRGBChannelCount(IsolateThread t) {
        try {
            return reader.getRGBChannelCount();
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_is_rgb")
    // if one openbytes call gives multiple colors
    static byte BFIsRGB(IsolateThread t) {
        try {
            return toCBoolean(reader.isRGB());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_is_interleaved")
    static byte BFIsInterleaved(IsolateThread t) {
        try {
            return toCBoolean(reader.isInterleaved());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_is_little_endian")
    static byte BFIsLittleEndian(IsolateThread t) {
        try {
            return toCBoolean(reader.isLittleEndian());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_is_floating_point")
    static byte BFIsFloatingPoint(IsolateThread t) {
        try {
            return toCBoolean(FormatTools.isFloatingPoint(reader));
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_floating_point_is_normalized")
    static byte BFIsNormalized(IsolateThread t) {
        // tells whether to normalize floating point data
        try {
            return toCBoolean(reader.isNormalized());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_get_dimension_order")
    static CCharPointer BFGetDimensionOrder(IsolateThread t) {
        try {
            return toCString(reader.getDimensionOrder()).get();
        } catch (Exception e) {
            saveError(e.toString());
            return WordFactory.nullPointer();
        }
    }

    @CEntryPoint(name = "bf_is_order_certain")
    static byte BFIsOrderCertain(IsolateThread t) {
        try {
            return toCBoolean(reader.isOrderCertain());
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bf_open_bytes")
    static int BFOpenBytes(IsolateThread t, int x, int y, int w, int h) {
        try {
            int size = w * h * FormatTools.getBytesPerPixel(reader.getPixelType()) * reader.getRGBChannelCount();
            if (size > communicationBuffer.length) {
                saveError("Requested tile too big; must be at most " + communicationBuffer.length
                        + " bytes but wanted " + size);
                return -2;
            }
            // TODO: for example for noninterleaved channels, we'll need to handle other
            // planes.
            // To implement that one would need to read the description of
            // https://downloads.openmicroscopy.org/bio-formats/5.4.1/api/loci/formats/IFormatReader.html#getEffectiveSizeC--
            // and understand the difference between getimagecount and getseriescount
            reader.openBytes(0, communicationBuffer, x, y, w, h);
            return size;
        } catch (Exception e) {
            saveError(e.toString());
            // This is permitted:
            // https://github.com/oracle/graal/blob/492c6016c5d9233be5de2dd9502cc81f746fc8e7/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/c/CTypeConversionSupportImpl.java#L55
            return -1;
        }
    }

    // An alternative to openBytes is openPlane
    // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/IFormatReader.html#openPlane-int-int-int-int-int-
    // some types are
    // https://github.com/search?q=repo%3Aome%2Fbioformats+getNativeDataType&type=code

    // https://bio-formats.readthedocs.io/en/latest/metadata-summary.html
    @CEntryPoint(name = "bf_get_mpp_x")
    // 0 if not defined, -1 for error
    static double BFGetMPPX(IsolateThread t) {
        try {
            // TODO: modify to handle multiple series
            var size = metadata.getPixelsPhysicalSizeX(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeX();
        } catch (Exception e) {
            saveError(e.toString());
            return -1d;
        }

    }

    @CEntryPoint(name = "bf_get_mpp_y")
    static double BFGetMPPY(IsolateThread t) {
        try {
            // TODO: modify to handle multiple series
            var size = metadata.getPixelsPhysicalSizeY(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeY();
        } catch (Exception e) {
            saveError(e.toString());
            return -1d;
        }

    }

    @CEntryPoint(name = "bf_get_mpp_z")
    static double BFGetMPPZ(IsolateThread t) {
        try {
            // TODO: modify to handle multiple series
            var size = metadata.getPixelsPhysicalSizeZ(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeZ();
        } catch (Exception e) {
            saveError(e.toString());
            return -1d;
        }
    }

    @CEntryPoint(name = "bf_get_current_file")
    static CCharPointer BFGetCurrentFile(IsolateThread t) {
        try {
            String file = reader.getCurrentFile();
            if (file == null) {
                return WordFactory.nullPointer();
            } else {
                return toCString(file).get();
            }
        } catch (Exception e) {
            saveError(e.toString());
            return WordFactory.nullPointer();
        }
    }

    @CEntryPoint(name = "bf_is_any_file_open")
    static byte BFIsAnyFileOpen(IsolateThread t) {
        try {
            return toCBoolean(reader.getCurrentFile() == null);
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bftools_should_generate")
    // Once a file is successfully opened, call this to see if we need to
    // regenerate the pyramid
    static byte BFToolsShouldGenerate(IsolateThread t) {
        try {
            int levels = reader.getResolutionCount();
            int previousX = 0;
            int previousY = 0;
            boolean shouldGenerate = false;
            for (int i = 0; i < levels; i++) {
                reader.setResolution(i);
                int x = reader.getSizeX();
                int y = reader.getSizeY();
                // I chose 4 to allow some margin
                // the strict alternative would bea bigger divisor or
                // "> 2*x+1"
                if (previousX > 2 * x + x / 4
                        || previousY > 2 * y + y / 4) {
                    shouldGenerate = true;
                    break;
                }
                previousX = x;
                previousY = y;
            }
            if (previousX > 320 && previousY > 320) {
                shouldGenerate = true;
            }
            return toCBoolean(shouldGenerate);
        } catch (Exception e) {
            saveError(e.toString());
            return -1;
        }
    }

    @CEntryPoint(name = "bftools_generate_subresolutions")
    // Outpath must have .ome.tiff extension
    // tiff or ome.tiff extensions can contain subresolutions
    // but bfconvert can sometimes produce results incompatible with others
    // https://forum.image.sc/t/bfconvert-breaks-images-with-alpha-layer-wrong-interleave/83482
    // so reserve ome.tiff for bioformats-reading
    static byte BFToolsGenerateSubresolutions(IsolateThread t, CCharPointer inPathPtr, CCharPointer outPathPtr,
            int layers) {
        // https://bio-formats.readthedocs.io/en/latest/developers/wsi.html#pyramids-in-ome-tiff
        // https://bio-formats.readthedocs.io/en/v6.14.0/users/comlinetools/conversion.html
        // https://bio-formats.readthedocs.io/en/v6.14.0/users/comlinetools/conversion.html#cmdoption-bfconvert-pyramid-scale
        // "jar tvf" on bioformats-tools.jar shows classes
        // Meta-inf says main is in loci.formats.tools.ImageInfo
        // But there are multiple entry points.
        try {
            String inPath = toJavaString(inPathPtr);
            String outPath = toJavaString(outPathPtr);

            ImageConverter.main(new String[] { "-noflat", "-pyramid-resolutions", Integer.toString(layers),
                    "-pyramid-scale", "2", inPath, outPath });
            reader.setId(outPath);
            return toCBoolean(true);
        } catch (Exception e) {
            saveError(e.toString());
            return toCBoolean(false);
        }
    }

    @CEntryPoint(name = "bfinternal_deleteme")
    static byte BFInternalDeleteme(IsolateThread t, CCharPointer file) {
        try {
            System.out.println("Making class");
            System.out.println(new org.libjpegturbo.turbojpeg.TJDecompressor());
            System.out.println("Made class");

            var s = toJavaString(file);

            // Works well:
            // var a = new FileInputStream(s);

            // Doesn't work:
            var a = new RandomAccessInputStream(s);
            var b = new BufferedInputStream(a, 81920);
            System.out.println(System.getProperty("java.library.path"));
            ImageInputStream stream = new MemoryCacheImageInputStream(b);
            return (byte) stream.readBit();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void close() {
        try {
            reader.close();
        } catch (Exception e) {

        }
    }

    // Debug function
    public static byte openFile(String filename) throws Exception {
        try {
            /*
             * var filee = new RandomAccessInputStream(
             * "/Users/zerf/Desktop/Screenshot 2023-06-30 at 15.31.08.png");
             * ImageInputStream streamold = new MemoryCacheImageInputStream(new
             * BufferedInputStream(filee));
             * var arr = (new ImageReader()).getPotentialReaders(filee);
             * System.out.println(arr);
             * reader.setFlattenedResolutions(false);
             * 
             * reader.setId(
             * "/Users/zerf/Downloads/Github-repos/CGDogan/camic-Distro/images/OS-1.ndpi.tiff"
             * );
             * reader.setResolution(1);
             * byte[] bytes = new byte[3145728];
             * reader.openBytes(0, bytes, 51200, 30720, 1024, 1024);
             */
            System.out.println("Making class");
            System.out.println(new org.libjpegturbo.turbojpeg.TJDecompressor());
            System.out.println("Made class");

            if (!new File("/images/OS-1.ndpi.tiff").isFile()) {
                System.out.println("file not found!");
            }

            File path2 = new File("/");
            File[] files2 = path2.listFiles();
            System.out.println("Dirlist: " + Arrays.toString(files2));

            File path1 = new File("/images/");
            File[] files1 = path1.listFiles();
            System.out.println("Dirlist: " + Arrays.toString(files1));

            // http://127.0.0.1:4010/img/IIP/raw/?DeepZoom=/images/OS-1.ndpi.tiff_files/17/76_16.jpg
            reader.setId("/images/OS-1.ndpi.tiff");
            reader.setResolution(0);
            byte[] bytes = new byte[3145728];
            reader.openBytes(0, bytes, 77824, 16384, 1024, 1024);
            // TJUnitTest.main(new String[0]);

            System.out.println("Step 1");
            File path = new File("/Users/zerf/Downloads/Github-repos/CGDogan/camic-Distro/images/");
            File[] files = path.listFiles();
            System.out.println("Dirlist: " + Arrays.toString(files));
            for (int i = 0; i < files.length; i++) {
                if (files[i].isFile()) {
                    try {
                        System.out.println("LLOP" + i);

                        // reader.getReader(files[i].getAbsolutePath());
                        close();
                        reader.setFlattenedResolutions(false);

                        reader.setId((files[i]).getAbsolutePath());

                        // reader.setMetadataStore(metadata);
                        System.out.println(files[i].getAbsolutePath());
                        close();
                    } catch (Exception e) {
                        System.out.println(e.toString());
                    }
                }
            }
            // reader.getReader("/Users/zerf/Downloads/Github-repos/CGDogan/camic-Distro/images/out2ewfrerwf_tiff_conv.tif");
            System.out.println("Step 1.2");

            ImageInputStream stream = new MemoryCacheImageInputStream(new BufferedInputStream(new FileInputStream(
                    "/Users/zerf/Desktop/Screenshot 2023-06-30 at 15.31.08.png"), 81920));
            var b = ImageIO.read(stream);

            System.out.println("start 1");
            reader.close(true);
            System.out.println("start 2.5?");

            reader.setId("/Users/zerf/Downloads/Github-repos/CGDogan/camic-Distro/images/posdebugfiles_2.dcm");
            System.out.println(reader.getRGBChannelCount());

            System.out.println("start 3");

            reader.openBytes(0);
            System.out.println("start 4");

            return 0;
        } catch (Exception e) {
            System.out.println("excepting incoming");
            throw e;
        }
    }

    private static void saveError(String s) {
        lastError.close();
        lastError = toCString(s);
    }

    public static void main(String args[]) throws Exception {
        int x = 10;
        int y = 25;
        int z = x + y;

        openFile("");
        // System.out.println("Sum of x+y = " + z);
    }
}
