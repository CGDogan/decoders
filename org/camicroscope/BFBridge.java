package org.camicroscope;

import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.ReaderWrapper;
 // https://downloads.openmicroscopy.org/bio-formats/7.0.0/api/loci/formats/FormatTools.html
import loci.formats.FormatTools;
// https://downloads.openmicroscopy.org/bio-formats/7.0.0/api/loci/formats/MetadataTools.html
//import loci.formats.MetadataTools;
// https://downloads.openmicroscopy.org/bio-formats/7.0.0/api/loci/formats/services/OMEXMLServiceImpl.html
//import loci.formats.services.OMEXMLServiceImpl;
import loci.formats.ome.OMEXMLMetadataImpl;
import loci.formats.Memoizer;
import loci.formats.ome.OMEXMLMetadata;
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
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

// https://bio-formats.readthedocs.io/en/v6.14.0/developers/file-reader.html#reading-files
// There are other utilities as well

import loci.common.RandomAccessInputStream;

public class BFBridge {
    // To allow both cached and noncached setup with one type:
    // ImageReader is our noncached reader but it doesn't
    // implement ReaderWrapper so make a wrapper to make substitution
    // possible with Memoizer
    private final class BFReaderWrapper extends ReaderWrapper {
        BFReaderWrapper(IFormatReader r) {
            super(r);
        }
    }

    // BioFormats doesn't give us control over thumbnail sizes
    // unless we define a wrapper for FormatTools.openThumbBytes
    // https://github.com/ome/bioformats/blob/9cb6cfaaa5361bcc4ed9f9841f2a4caa29aad6c7/components/formats-api/src/loci/formats/FormatTools.java#L1287
    private final class BFThumbnailWrapper extends ReaderWrapper {
        // exact sizes
        private int thumbX = 256;
        private int thumbY = 256;

        // For our use
        void setThumbSizeX(int x) {
            thumbX = x;
        }

        void setThumbSizeY(int y) {
            thumbY = y;
        }

        // For FormatTools.openThumbBytes
        @Override
        public int getThumbSizeX() {
            return thumbX;
        }

        @Override
        public int getThumbSizeY() {
            return thumbY;
        }

        BFThumbnailWrapper(IFormatReader r) {
            super(r);
        }
    }

    private final BFThumbnailWrapper readerWithThumbnailSizes;
    private final ReaderWrapper reader;

    // Our uncaching internal reader. ImageReader and ReaderWrapper
    // both implement IFormatReader but if you need an ImageReader-only
    // method, access this. (You could also do (inefficiently)
    // .getReader() on "reader" and cast it to ImageReader
    // since that's what we use)
    private final ImageReader nonCachingReader = new ImageReader();

    // As a summary, nonCachingReader is the reader
    // which is wrapped by BFReaderWrapper or Memoizer
    // which is sometimes wrapped by readerWithThumbnailSizes
    // For performance, this library calls the readerWithThumbnailSizes
    // wrapper only when it needs thumbnail.
    // Please note that reinstantiating nonCachingReader requires
    // reinstantiating "ReaderWrapper reader" (BFReaderWrapper or Memoizer).
    // And reinstantiating the latter requires reinstantiating
    // the readerWithThumbnailSizes
    private final OMEXMLMetadataImpl metadata = new OMEXMLMetadataImpl();

    // javac -Dbfbridge.cachedir=/tmp/cachedir for faster file loading
    private static final File cachedir;

    static {
        // Initialize cache
        String cachepath = System.getProperty("bfbridge.cachedir");
        System.out.println("Trying bfbridge cache directory: " + cachepath);

        File _cachedir = null;
        if (cachepath == null || cachepath.equals("")) {
            System.out.println("Skipping bfbridge cache");
        } else {
            _cachedir = new File(cachepath);
        }
        if (_cachedir != null && !_cachedir.exists()) {
            System.out.println("bfbridge cache directory does not exist, skipping!");
            _cachedir = null;
        }
        if (_cachedir != null && !_cachedir.isDirectory()) {
            System.out.println("bfbridge cache directory is not a directory, skipping!");
            _cachedir = null;
        }
        if (_cachedir != null && !_cachedir.canRead()) {
            System.out.println("cannot read from the bfbridge cache directory, skipping!");
            _cachedir = null;
        }
        if (_cachedir != null && !_cachedir.canWrite()) {
            System.out.println("cannot write to the bfbridge cache directory, skipping!");
            _cachedir = null;
        }
        if (_cachedir != null) {
            System.out.println("activating bfbridge cache");
        }
        cachedir = _cachedir;
    }

    // Initialize our instance reader
    {
        if (cachedir == null) {
            reader = new BFReaderWrapper(nonCachingReader);
        } else {
            reader = new Memoizer(nonCachingReader, cachedir);
        }

        // Use the easier resolution API
        reader.setFlattenedResolutions(false);
        reader.setMetadataStore(metadata);
        // Save format-specific metadata as well?
        // metadata.setOriginalMetadataPopulated(true);

        readerWithThumbnailSizes = new BFThumbnailWrapper(reader);
    }

    private static final Charset charset = Charset.forName("UTF-8");
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

    // functions that use communicationBuffer must call
    // communicationBuffer.rewind() before reading/writing
    // If the last write to the communication buffer
    // isn't an error, lastErrorBytes
    // is not updated
    private int lastErrorBytes = 0;

    void BFSetCommunicationBuffer(ByteBuffer b) {
        communicationBuffer = b;
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
            // and we would access only through the WrappedReader/Memoizer
            // and not the ImageReader

            close();
            return nonCachingReader.getReader(new String(filename)) != null ? 1 : 0;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        } finally {
            close();
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

    // Input Parameter: first filenameLength bytes of communicationBuffer
    int BFOpen(int filenameLength) {
        try {
            byte[] filename = new byte[filenameLength];
            communicationBuffer.rewind().get(filename);
            close();
            reader.setId(new String(filename));
            return 1;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            close();
            return -1;
        }
    }

    // writes to communicationBuffer and returns the number of bytes written
    int BFGetFormat() {
        try {
            byte[] formatBytes = reader.getFormat().getBytes(charset);
            communicationBuffer.rewind().put(formatBytes);
            return formatBytes.length;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // If expected to be the single file; always true for single-file formats
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

    // writes to communicationBuffer and returns the number of bytes written
    // 0 if no file was opened
    // remember that this function likely returns a full path
    int BFGetCurrentFile() {
        try {
            String file = reader.getCurrentFile();
            if (file == null) {
                return 0;
            } else {
                byte[] characters = file.getBytes(charset);
                communicationBuffer.rewind().put(characters);
                return characters.length;
            }
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // Lists null-separated filenames. Returns bytes written including the last null
    int BFGetUsedFiles() {
        try {
            communicationBuffer.rewind();
            String[] files = reader.getUsedFiles();
            int charI = 0;
            for (String file : files) {
                byte[] characters = file.getBytes(charset);
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

    int BFClose() {
        try {
            reader.close();
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

    // with the easy viewing api we use, a series is an independent one.
    // A single image or a multilayer pyramid.
    int BFSetCurrentSeries(int no) {
        try {
            reader.setSeries(no);
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

    int BFGetSizeC() {
        try {
            return reader.getSizeC();
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

    int BFGetEffectiveSizeC() {
        try {
            return reader.getEffectiveSizeC();
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

    // writes to communicationBuffer and returns the number of bytes written
    int BFGetDimensionOrder() {
        try {
            byte[] strBytes = reader.getDimensionOrder().getBytes(charset);
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

    // https://downloads.openmicroscopy.org/bio-formats/7.0.0/api/loci/formats/IFormatReader.html#isFalseColor--
    // when we have 8 or 16 bits per channel, these might be signifying
    // indices in color profile.
    // isindexed false, isfalsecolor false -> no table
    // isindexed true, isfalsecolor false-> table must be read
    // isindexed true, isfalsecolor true-> table can be read, not obligatorily
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

    // Serializes a 2D table with 2nd dimension length 256
    int BFGet8BitLookupTable() {
        try {
            byte[][] table = reader.get8BitLookupTable();
            int len = table.length;
            int sublen = table[0].length;
            if (sublen != 256) {
                saveError("BFGet8BitLookupTable expected 256 rowlength");
                return -2;
            }
            byte[] table1D = new byte[len * sublen];
            for (int i = 0; i < len; i++) {
                for (int j = 0; j < sublen; j++) {
                    table1D[i * sublen + j] = table[i][j];
                }
            }
            communicationBuffer.rewind().put(table1D);
            return len * sublen;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // Returns total number of bytes written
    int BFGet16BitLookupTable() {
        try {
            short[][] table = reader.get16BitLookupTable();
            int len = table.length;
            int sublen = table[0].length;
            if (sublen != 65536) {
                saveError("BFGet16BitLookupTable expected 65536 rowlength");
                return -2;
            }
            short[] table1D = new short[len * sublen];
            for (int i = 0; i < len; i++) {
                for (int j = 0; j < sublen; j++) {
                    table1D[i * sublen + j] = table[i][j];
                }
            }
            communicationBuffer.rewind();
            for (int i = 0; i < table1D.length; i++) {
                communicationBuffer.putShort(table1D[i]);
            }
            return 2 * len * sublen;
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1;
        }
    }

    // plane is 0, default
    // writes to communicationBuffer and returns the number of bytes written
    int BFOpenBytes(int plane, int x, int y, int w, int h) {
        try {
            // https://github.com/ome/bioformats/issues/4058 means that
            // openBytes wasn't designed to copy to a preallocated byte array
            // unless it had the exact size and not greater
            byte[] bytes = reader.openBytes(0, x, y, w, h);
            communicationBuffer.rewind().put(bytes);
            return bytes.length;
        } catch (Exception e) {
            // Was it because of exceeding buffer?
            // https://github.com/ome/bioformats/blob/4a08bfd5334323e99ad57de00e41cd15706164eb/components/formats-api/src/loci/formats/FormatReader.java#L906
            // https://downloads.openmicroscopy.org/bio-formats/6.13.0/api/loci/formats/ImageReader.html#openBytes-int-byte:A-
            try {
                int size = w * h * FormatTools.getBytesPerPixel(reader.getPixelType()) * reader.getRGBChannelCount();
                if (size > communicationBuffer.capacity()) {
                    saveError("Requested tile too big; must be at most " + communicationBuffer.capacity()
                            + " bytes but wanted " + size);
                    return -2;
                }
            } catch (Exception e2) {
            } finally {
                saveError(getStackTrace(e));
                return -1;
            }
        }
    }

    // warning: changes the current resolution level
    // takes exact width and height.
    // the caller should ensure the correct aspect ratio.
    // writes to communicationBuffer and returns the number of bytes written
    // prepares 3 channel or 4 channel, same sample format
    // and bitlength (but made unsigned if was int8 or int16 or int32)
    int BFOpenThumbBytes(int plane, int width, int height) {
        try {
            /*
             * float yOverX = reader.getSizeY() / reader.getSizeX();
             * float xOverY = 1/yToX;
             * int width = Math.min(maxWidth, maxHeight * xOverY);
             * int height = Math.min(maxHeight, maxWidth * yOverX);
             * Also potentially a check so that if width is greater than getSizeX
             * or likewise for height, use image resolution.
             */

            readerWithThumbnailSizes.setThumbSizeX(width);
            readerWithThumbnailSizes.setThumbSizeY(height);

            int resCount = reader.getResolutionCount();
            reader.setResolution(resCount - 1);

            // Using class's openThumbBytes
            // instead of FormatTools.openThumbBytes 
            // might break our custom thumbnail sizes?
            byte[] bytes = FormatTools.openThumbBytes(readerWithThumbnailSizes, plane);
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

    // series is 0, default
    // https://bio-formats.readthedocs.io/en/latest/metadata-summary.html
    // 0 if not defined, -1 for error
    double BFGetMPPX(int series) {
        try {
            // Maybe consider modifying to handle multiple series
            var size = metadata.getPixelsPhysicalSizeX(series);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }

    }

    double BFGetMPPY(int series) {
        try {
            var size = metadata.getPixelsPhysicalSizeY(series);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }
    }

    double BFGetMPPZ(int series) {
        try {
            var size = metadata.getPixelsPhysicalSizeZ(series);
            if (size == null) {
                return 0d;
            }
            return size.value(UNITS.MICROMETER).doubleValue();
        } catch (Exception e) {
            saveError(getStackTrace(e));
            return -1d;
        }
    }

    int BFDumpOMEXMLMetadata() {
        try {
            String metadataString = metadata.dumpXML();
            byte[] bytes = metadataString.getBytes(charset);
            if (bytes.length > communicationBuffer.capacity()) {
                saveError("BFDumpOMEXMLMetadata: needed buffer of length at least " + bytes.length + " but current buffer is of length " + communicationBuffer.capacity());
                return -2;
            }
            communicationBuffer.rewind().put(bytes);
            return bytes.length;
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

    // TODO: this function is not ready
    // -series 0 maybe?
    // compression
    // https://github.com/ome/bioformats/blob/develop/components/formats-bsd/src/loci/formats/codec/CompressionType.java#L47
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

            // TODO: series 0 and compression arg
            ImageConverter.main(new String[] { "-noflat", "-pyramid-resolutions", Integer.toString(numberOfLayers),
                    "-pyramid-scale", "2", "-series", "0", "-compression", "JPEG-2000 Lossy", inPath, outPath });
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
            /*
             * reader.setId("/images/OS-1.ndpi.tiff");
             * reader.setResolution(0);
             * byte[] bytes = new byte[3145728];
             * reader.openBytes(0, bytes, 77824, 16384, 1024, 1024);
             */
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
        byte[] errorBytes = s.getBytes(charset);
        int bytes_len = errorBytes.length;
        // -1 to account for the null byte for security
        bytes_len = Math.min(bytes_len, Math.max(communicationBuffer.capacity() - 1, 0));
        // Trim error message
        communicationBuffer.rewind().put(errorBytes, 0, bytes_len);
        lastErrorBytes = bytes_len;
    }

    public static void main(String args[]) throws Exception {
        int x = 10;
        int y = 25;
        int z = x + y;

        (new BFBridge()).openFile("");
        // System.out.println("Sum of x+y = " + z);
    }
}
