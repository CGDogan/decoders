import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

public class Testimageio {
    public static void main(String[] a) {
        try {
            ImageInputStream stream = new MemoryCacheImageInputStream(new BufferedInputStream(
                    new ByteArrayInputStream(
                            new byte[] {
                                    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) 0x00, (byte) 0x10,
                                    (byte) 0x4A, (byte) 0x46
                            }),
                    81920));
            var b = ImageIO.read(stream);
        } catch (Exception e) {
            // Exception: We've run the JPEG decoder and it decided that it was bad image!
            System.out.println("Seems to work");
        } catch (Error e) {
            // Error: The JPEG decoder was chosen however it could not be loaded
            System.out.println("Broken. " + e.toString());
        }
    }
}
