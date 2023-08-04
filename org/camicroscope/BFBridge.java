package org.camicroscope;

import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.FormatTools; // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/FormatTools.html
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.services.JPEGTurboServiceImpl;
import ome.units.UNITS;

import loci.formats.tools.ImageConverter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

// https://bio-formats.readthedocs.io/en/v6.14.0/developers/file-reader.html#reading-files
// There are other utilities as well

import loci.common.RandomAccessInputStream;

public class BFBridge {
    private ImageReader reader = new ImageReader();
    private IMetadata metadata = MetadataTools.createOMEXMLMetadata();
    {
        // Use the easier resolution API
        reader.setFlattenedResolutions(false);
        reader.setMetadataStore(metadata);
        // Save file-specific metadata as well?
        // metadata.setOriginalMetadataPopulated(true);
    }

    // If we need to encode special characters please see
    // https://stackoverflow.com/a/17737968
    private ByteBuffer communicationBuffer = null;
    // Design decisions of this library:
    // There are two ways to communicate:
    // 1) https://stackoverflow.com/a/26605880 allocate byte[] from C
    // also see https://stackoverflow.com/a/4083678
    // or GetPrimitiveArrayCritical for fast access
    // these likely won't copy
    // but I chose the second one
    // 2) NewDirectByteBuffer: set ByteBuffer to native memory from C
    // I think the difference between them is just the API

    // How we use the communicationBuffer:
    // 1) read region, etc. write to it from the beginning and return bytes written
    // 2) read region negative, so write an error.
    // to classify the error, see what code branch returns it
    // to display a message, "lastErrorBytes" many bytes were written as error to
    // communicationBuffer so display its that many bytes.
    // communicationBuffer does not usually null terminate.
    // remember to: (std::string s).assign(ptr, size) or ptr[size] = 0;

    // Remember:
    // functions that use communicationBuffer should call
    // communicationBuffer.rewind() before reading/writing
    private int lastErrorBytes = 0;

    void BFSetCommunicationBuffer(ByteBuffer b) {
        communicationBuffer = b;
    }

    // the user should clear communicationBuffer
    void BFReset() {
        close();
        communicationBuffer = null;
    }

    int BFGetErrorLength() {
        return lastErrorBytes;
    }

    // Please note: this closes the previous file
    // Input Parameter: first filenameLength bytes of communicationBuffer.
    int BFIsCompatible(int filenameLength) {
        try {
            byte[] filename = new byte[filenameLength];
            communicationBuffer.rewind().get(filename);
            // If we didn't have this line, I would change
            // "private ImageReader reader" to
            // "private IFormatReader reader"
            return reader.getReader(new String(filename)) != null ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
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

    // Do not open another file without closing current
    // Input Parameter: first filenameLength bytes of communicationBuffer
    int BFOpen(int filenameLength) {
        try {
            byte[] filename = new byte[filenameLength];
            communicationBuffer.rewind().get(filename);
            reader.setId(new String(filename));
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            close();
            return -1;
        }
    }

    // If expected to be the single file, or always true for single-file formats
    // Input Parameter: first filenameLength bytes of communicationBuffer.
    int BFIsSingleFile(int filenameLength) {
        try {
            byte[] filename = new byte[filenameLength];
            communicationBuffer.rewind().get(filename);

            close();
            return reader.isSingleFile(new String(filename)) ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        } finally {
            close();
        }
    }

    // Lists null-separated filenames. Returns bytes written including the last null
    int BFGetUsedFiles() {
        try {
            communicationBuffer.rewind();
            String[] files = reader.getUsedFiles();
            int charI = 0;
            for (String file : files) {
                byte[] characters = file.getBytes();
                if (characters.length + 2 > communicationBuffer.capacity()) {
                    saveError("Too long");
                    return -2;
                }
                communicationBuffer.put(characters);
                communicationBuffer.put((byte) 0);
                charI += characters.length + 1;
            }
            return charI;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // writes to communicationBuffer and returns the number of bytes written
    // 0 if no file was opened
    // remember that this function likely returns a full path
    int BFGetCurrentFile() {
        try {
            String file = reader.getCurrentFile();
            if (file == null) {
                return 0;
            } else {
                byte[] characters = file.getBytes();
                communicationBuffer.rewind().put(characters);
                return characters.length;
            }
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFClose() {
        try {
            reader.close();
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetResolutionCount() {
        try {
            // In resolution mode, each of series has a number of resolutions
            // WSI pyramids have multiple and others have one
            // This method returns resolution counts for the current series
            return reader.getResolutionCount();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFSetCurrentResolution(int resIndex) {
        try {
            // Precondition: The caller must check that at least 0 and less than
            // resolutionCount
            reader.setResolution(resIndex);
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    /*
     * // These aren't useful because we use setFlattenedResolutions(false)
     * int BFSetCoreIndex(int ) {
     * // see getSeriesCount. I don't know if this is a useful method?
     * 
     * try {
     * // Precondition: The caller must check that at least 0 and less than
     * // resolutionCount
     * reader.setCoreIndex(resIndex);
     * return 1;
     * } catch (Exception e) {
     * saveError(getStackTrace(e));
     * return -1;
     * }
     * }
     * 
     * int BFGetCoreCount() {
     * }
     */

    // with the easy viewing api we use, a series is an independent one.
    // A single image or a multilayer pyramid.
    int BFSetSeries(int no) {
        try {
            reader.setSeries(no);
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSeriesCount() {
        try {
            return reader.getSeriesCount();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSizeX() {
        try {
            // For current resolution
            return reader.getSizeX();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSizeY() {
        try {
            return reader.getSizeY();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSizeZ() {
        try {
            return reader.getSizeZ();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSizeT() {
        try {
            return reader.getSizeT();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetSizeC() {
        try {
            return reader.getSizeC();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetEffectiveSizeC() {
        try {
            return reader.getEffectiveSizeC();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetOptimalTileWidth() {
        try {
            return reader.getOptimalTileWidth();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFGetOptimalTileHeight() {
        try {
            return reader.getOptimalTileHeight();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // writes to communicationBuffer and returns the number of bytes written
    int BFGetFormat() {
        try {
            byte[] formatBytes = reader.getFormat().getBytes();
            communicationBuffer.rewind().put(formatBytes);
            return formatBytes.length;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // Internal BioFormats pixel type
    int BFGetPixelType() {
        try {
            // https://github.com/ome/bioformats/blob/4a08bfd5334323e99ad57de00e41cd15706164eb/components/formats-api/src/loci/formats/FormatReader.java#L735
            // https://github.com/ome/bioformats/blob/9cb6cfaaa5361bcc4ed9f9841f2a4caa29aad6c7/components/formats-api/src/loci/formats/FormatTools.java#L835
            // https://github.com/ome/bioformats/blob/9cb6cfaaa5361bcc4ed9f9841f2a4caa29aad6c7/components/formats-api/src/loci/formats/FormatTools.java#L1507
            return reader.getPixelType();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // The name is misleading
    // Actually this gives bits per pixel per channel!
    // openBytes documentation makes this clear
    // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/ImageReader.html#openBytes-int-byte:A-
    int BFGetBitsPerPixel() {
        // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/IFormatReader.html#getPixelType--
        // https://github.com/ome/bioformats/blob/9cb6cfaaa5361bcc4ed9f9841f2a4caa29aad6c7/components/formats-api/src/loci/formats/FormatTools.java#L96
        try {
            return reader.getBitsPerPixel();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // gives bytes per pixel in a channel
    int BFGetBytesPerPixel() {
        try {
            return FormatTools.getBytesPerPixel(reader.getPixelType());
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // (Almost?) always equal to sizeC. Can be 3, can be 4.
    int BFGetRGBChannelCount() {
        try {
            return reader.getRGBChannelCount();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // number of planes in current series.
    int BFGetImageCount() {
        /*
         * From BioFormats docs:
         * getEffectiveSizeC() * getSizeZ() * getSizeT() == getImageCount() regardless
         * of the result of isRGB().
         */
        try {
            return reader.getImageCount();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // if one openbytes call gives multiple colors
    // ie, if BFGetImageCount many calls are needed to openbytes
    int BFIsRGB() {
        try {
            return reader.isRGB() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFIsInterleaved() {
        try {
            return reader.isInterleaved() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFIsLittleEndian() {
        try {
            return reader.isLittleEndian() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    /*
     * // Warning: this check is that image contains at least one floating point
     * subimage
     * // not of the current resolution/series
     * // so I'm disabling this
     * int BFIsFloatingPoint() {
     * try {
     * return FormatTools.isFloatingPoint(reader) ? 1 : 0;
     * } catch (Exception e) {
     * saveError(getStackTrace(e));
     * return -1;
     * }
     * }
     */

    // https://downloads.openmicroscopy.org/bio-formats/6.14.0/api/loci/formats/IFormatReader.html#isFalseColor--
    // when we have 8 or 16 bits per channel, these might be signifying
    // indices in color profile.
    // isindexed false, isfalsecolor false -> no table
    // isindexed true, isfalsecolor false-> table must be read
    // isindexed true, isfalsecolor true-> table can be read for precision, not
    // obligatorily
    int BFIsFalseColor() {
        // note: lookup tables need to be cached by us
        // as some readers such as
        // https://github.com/ome/bioformats/blob/65db5eb2bb866ebde42c8d6e2611818612432828/components/formats-bsd/src/loci/formats/in/OMETiffReader.java#L310
        // do not serve from cache
        try {
            return reader.isFalseColor() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFIsIndexedColor() {
        try {
            return reader.isIndexed() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // writes to communicationBuffer and returns the number of bytes written
    int BFGetDimensionOrder() {
        try {
            byte[] strBytes = reader.getDimensionOrder().getBytes();
            communicationBuffer.rewind().put(strBytes);
            return strBytes.length;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    int BFIsOrderCertain() {
        try {
            return reader.isOrderCertain() ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // writes to communicationBuffer and returns the number of bytes written
    int BFOpenBytes(int x, int y, int w, int h) {
        try {
            // https://github.com/ome/bioformats/blob/4a08bfd5334323e99ad57de00e41cd15706164eb/components/formats-api/src/loci/formats/FormatReader.java#L906
            int size = w * h * FormatTools.getBytesPerPixel(reader.getPixelType()) * reader.getRGBChannelCount();
            if (size > communicationBuffer.capacity()) {
                saveError("Requested tile too big; must be at most " + communicationBuffer.capacity()
                        + " bytes but wanted " + size);
                return -2;
            }

            // https://github.com/ome/bioformats/issues/4058 means that
            // openBytes wasn't designed to copy to a preallocated byte array
            // unless it had the exact size and not greater
            byte[] bytes = reader.openBytes(0, x, y, w, h);
            communicationBuffer.rewind().put(bytes);
            return bytes.length;

        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // An alternative to openBytes is openPlane
    // https://downloads.openmicroscopy.org/bio-formats/latest/api/loci/formats/IFormatReader.html#openPlane-int-int-int-int-int-
    // some types are
    // https://github.com/search?q=repo%3Aome%2Fbioformats+getNativeDataType&type=code

    // https://bio-formats.readthedocs.io/en/latest/metadata-summary.html
    // 0 if not defined, -1 for error
    double BFGetMPPX() {
        try {
            // Maybe consider modifying to handle multiple series
            var size = metadata.getPixelsPhysicalSizeX(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeX();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }

    }

    double BFGetMPPY() {
        try {
            var size = metadata.getPixelsPhysicalSizeY(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeY();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }

    }

    double BFGetMPPZ() {
        try {
            var size = metadata.getPixelsPhysicalSizeZ(0);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue() / reader.getSizeZ();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }
    }

    int BFIsAnyFileOpen() {
        try {
            return reader.getCurrentFile() != null ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // Once a file is successfully opened, call this to see if we need to
    // regenerate the pyramid.
    // TODO: should we be less picky and measure if we have at least two layers?
    int BFToolsShouldGenerate() {
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
            return shouldGenerate ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // input: two consecutive filepaths in communicationBuffer
    // .dcm output is suggested for OpenSlide compatibility
    // otherwise Leica scn is an alternative as it uses BigTiff.
    // this function closes the currently open files if any
    int BFToolsGenerateSubresolutions(int filepathLength1, int filepathLength2, int numberOfLayers) {
        // https://bio-formats.readthedocs.io/en/latest/developers/wsi.html#pyramids-in-ome-tiff
        // https://bio-formats.readthedocs.io/en/v6.14.0/users/comlinetools/conversion.html
        // https://bio-formats.readthedocs.io/en/v6.14.0/users/comlinetools/conversion.html#cmdoption-bfconvert-pyramid-scale
        // "jar tvf" on bioformats-tools.jar shows classes
        // Meta-inf says main is in loci.formats.tools.ImageInfo
        // But there are multiple entry points.
        try {
            byte[] filepath1 = new byte[filepathLength1];
            byte[] filepath2 = new byte[filepathLength2];
            communicationBuffer.rewind().get(filepath1).get(filepath2);

            String inPath = new String(filepath1);
            String outPath = new String(filepath2);

            ImageConverter.main(new String[] { "-noflat", "-pyramid-resolutions", Integer.toString(numberOfLayers),
                    "-pyramid-scale", "2", inPath, outPath });
            // verify valid file
            close();
            reader.setId(outPath);
            close();
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    private static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return t.toString() + "\n" + sw.toString();
    }

    private void close() {
        try {
            reader.close();
        } catch (Exception e) {

        }
    }

    // Debug function
    public int openFile(String filename) throws Exception {
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

    private void saveError(String s) {
        byte[] errorBytes = s.getBytes();
        communicationBuffer.rewind().put(errorBytes);
        lastErrorBytes = errorBytes.length;
    }

    public static void main(String args[]) throws Exception {
        int x = 10;
        int y = 25;
        int z = x + y;

        (new BFBridge()).openFile("");
        // System.out.println("Sum of x+y = " + z);
    }
}
