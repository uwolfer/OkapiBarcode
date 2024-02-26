/*
 * Copyright 2024 Daniel Gredler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.okapibarcode.backend;

import static java.lang.Integer.toHexString;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.org.okapibarcode.util.Strings.toPrintableAscii;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;
import org.reflections.Reflections;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.aztec.AztecReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.maxicode.MaxiCodeReader;
import com.google.zxing.oned.CodaBarReader;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.oned.Code39Reader;
import com.google.zxing.oned.Code93Reader;
import com.google.zxing.oned.EAN13Reader;
import com.google.zxing.oned.EAN8Reader;
import com.google.zxing.oned.ITFReader;
import com.google.zxing.oned.UPCAReader;
import com.google.zxing.oned.UPCEReader;
import com.google.zxing.oned.rss.RSS14Reader;
import com.google.zxing.oned.rss.expanded.RSSExpandedReader;
import com.google.zxing.pdf417.PDF417Reader;
import com.google.zxing.pdf417.PDF417ResultMetadata;
import com.google.zxing.qrcode.QRCodeReader;

import uk.org.okapibarcode.backend.Symbol.DataType;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.Java2DRenderer;
import uk.org.okapibarcode.util.Strings;

/**
 * <p>Scans the test resources for file-based barcode tests.
 *
 * <p>Test files match the following naming convention:
 *
 * <pre>
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].properties
 *   /src/test/resources/uk/org/okapibarcode/backend/[symbol-name]/[test-name].png
 * </pre>
 *
 * <p>The test properties files contain different sections, each section indicated by a header line:
 * <ul>
 *     <li>PROPERTIES: The symbol configuration used to run the test. Each line in this section is of
 *         the format [property-name]=[property-value]. Empty lines can be used to delimit multiple tests
 *         within a single file, as long as the expected output is the same for all of the tests.
 *
 *     <li>LOG: The expected output symbol encoding information log. This expected log is checked against
 *         the actual log to verify that encoding worked as expected. This section will be omitted when a
 *         test is meant to verify an error scenario. Some symbols do not generate any log information;
 *         this section will always be omitted for such symbols.
 *
 *     <li>CODEWORDS: The expected output symbol codewords (or in some cases symbol pattern). The expected
 *         codewords are checked against the actual codewords to verify that encoding worked as expected.
 *         This section will be omitted when a test is meant to verify an error scenario.
 *
 *     <li>ERROR: The expected error message, used to check error scenarios. This section will be omitted
 *         when a test is meant to verify a success scenario.
 * </ul>
 *
 * <p>Test properties files should use the platform line separator. Lines that begin with "#" in the test
 * properties files are ignored.
 *
 * <p>If a test properties file is missing the expectation sections (LOG, CODEWORDS, ERROR), we assume
 * that it was recently added to the test suite and that we need to generate suitable expectation sections
 * to the file. This is done automatically the first time that the test is run.
 *
 * <p>The PNG files contain the expected rendering of the symbol, and are also checked to verify that
 * encoding worked as expected. PNG files are checked against the actual Okapi output, and are also fed
 * to ZXing (when an appropriate {@link Reader} is available) to verify that ZXing can read the barcode.
 * PNG files do not exist for tests intended to verify error scenarios.
 */
public class SymbolTest {

    /** The system end-of-line character sequence. */
    private static final String EOL = System.lineSeparator();

    /** The directory to which barcode images (expected and actual) are saved when an image check fails. */
    private static final File TEST_FAILURE_IMAGES_DIR = new File("build", "test-failure-images");

    /** The font used to render human-readable text when drawing the symbologies; allows for consistent results across operating systems. */
    public static final Font DEJA_VU_SANS;

    static {
        String path = "/uk/org/okapibarcode/fonts/OkapiDejaVuSans.ttf";
        try {
            InputStream is = SymbolTest.class.getResourceAsStream(path);
            DEJA_VU_SANS = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean registered = ge.registerFont(DEJA_VU_SANS);
            assertTrue(registered, "Unable to register test font!");
        } catch (IOException | FontFormatException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Runs the test. If there are no expectations yet, we generate them instead of checking against them.
     *
     * @param symbolType the type of symbol being tested
     * @param config the test configuration, as read from the test properties file
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param symbolName the name of the symbol type (used only for test naming)
     * @param fileBaseName the base name of the test file (used only for test naming)
     * @throws Exception if any error occurs during the test
     */
    @ParameterizedTest(name = "test {index}: {3}: {4}")
    @MethodSource("data")
    public void test(Class< ? extends Symbol > symbolType, TestConfig config, File pngFile, String symbolName, String fileBaseName) throws Exception {

        Symbol symbol = symbolType.getDeclaredConstructor().newInstance();
        symbol.setFontName(DEJA_VU_SANS.getFontName());

        Throwable actualError;

        try {
            setProperties(symbol, config.properties);
            actualError = null;
        } catch (InvocationTargetException e) {
            actualError = e.getCause();
        }

        if (config.hasSuccessExpectations() && pngFile.exists()) {
            verifySuccess(config, pngFile, symbol, actualError);
        } else if (config.hasErrorExpectations()) {
            verifyError(config, pngFile, actualError);
        } else {
            addMissingExpectations(config, pngFile, symbol, actualError);
        }
    }

    /**
     * Verifies that the specified symbol was encoded and rendered in a way that matches expectations.
     *
     * @param config the test configuration, as read from the test properties file
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param symbol the symbol to check
     * @param actualError the actual exception
     * @throws IOException if there is any I/O error
     * @throws ReaderException if ZXing has an issue decoding the barcode image
     */
    private static void verifySuccess(TestConfig config, File pngFile, Symbol symbol, Throwable actualError) throws IOException, ReaderException {

        assertEquals(null, actualError, "error");

        // try to verify logs
        String info = symbol.getEncodeInfo();
        String[] actualLog = (!info.isEmpty() ? symbol.getEncodeInfo().split("\n") : new String[0]);
        assertEquals(config.expectedLog.size(), actualLog.length, "log size");
        for (int i = 0; i < actualLog.length; i++) {
            String expected = config.expectedLog.get(i).trim();
            String actual = actualLog[i].trim();
            assertEquals(expected, actual, "at log line " + i);
        }

        try {
            // try to verify codewords
            int[] actualCodewords = symbol.getCodewords();
            assertEquals(config.expectedCodewords.size(), actualCodewords.length, "codeword count");
            for (int i = 0; i < actualCodewords.length; i++) {
                int expected = Integer.parseInt(config.expectedCodewords.get(i));
                int actual = actualCodewords[i];
                assertEquals(expected, actual, "at codeword index " + i);
            }
        } catch (UnsupportedOperationException e) {
            // codewords aren't supported, try to verify patterns
            String[] actualPatterns = symbol.pattern;
            assertEquals(config.expectedCodewords.size(), actualPatterns.length);
            for (int i = 0; i < actualPatterns.length; i++) {
                String expected = config.expectedCodewords.get(i);
                String actual = actualPatterns[i];
                assertEquals(expected, actual, "at pattern index " + i);
            }
        }

        // make sure the barcode images match
        String parentName = pngFile.getParentFile().getName();
        String pngName = pngFile.getName();
        String dirName = parentName + "-" + pngName.substring(0, pngName.lastIndexOf('.'));
        BufferedImage expected = ImageIO.read(pngFile);
        BufferedImage actual = draw(symbol);
        assertEqual(expected, actual, dirName);

        // if possible, ensure an independent third party (ZXing) can read the generated barcode and agrees on what it represents
        Reader zxingReader = findReader(symbol);
        if (zxingReader != null) {
            Result result = decode(actual, zxingReader);
            String zxingData = massageZXingData(result.getText(), symbol);
            String okapiData = massageOkapiData(symbol.getContent(), symbol);
            assertEquals(toPrintableAscii(okapiData), toPrintableAscii(zxingData), "checking against ZXing results");
            verifyMetadata(symbol, result);
        }

        // TODO: check against Zint?
    }

    /**
     * Returns a ZXing reader that can read the specified symbol.
     *
     * @param symbol the symbol to be read
     * @return a ZXing reader that can read the specified symbol
     */
    private static Reader findReader(Symbol symbol) {

        if (symbol instanceof Code128 ||
            symbol instanceof UspsPackage ||
            symbol instanceof DpdCode ||
            symbol instanceof Nve18) {
            return new Code128Reader();
        } else if (symbol instanceof Code93) {
            return new Code93Reader();
        } else if (symbol instanceof Code3Of9) {
            Code3Of9 code39 = (Code3Of9) symbol;
            boolean checkDigit = (code39.getCheckDigit() == Code3Of9.CheckDigit.MOD43);
            return new Code39Reader(checkDigit, false);
        } else if (symbol instanceof Code3Of9Extended) {
            Code3Of9Extended code39 = (Code3Of9Extended) symbol;
            boolean checkDigit = (code39.getCheckDigit() == Code3Of9Extended.CheckDigit.MOD43);
            return new Code39Reader(checkDigit, true);
        } else if (symbol instanceof Codabar) {
            return new CodaBarReader();
        } else if (symbol instanceof Code2Of5 && !symbol.getContent().isEmpty()) {
            Code2Of5 tof = (Code2Of5) symbol;
            if (tof.getMode() == Code2Of5.ToFMode.INTERLEAVED ||
                tof.getMode() == Code2Of5.ToFMode.INTERLEAVED_WITH_CHECK_DIGIT ||
                tof.getMode() == Code2Of5.ToFMode.ITF14) {
                // ZXing does not currently support empty content in ITF symbols
                // ZXing does not currently support non-interleaved 2-of-5 variants
                return new ITFReader();
            }
        } else if (symbol instanceof AztecCode &&
                   symbol.getDataType() != DataType.GS1 &&
                   symbol.getEciMode() == 3 &&
                  !symbol.getReaderInit() &&
                  !symbol.getContent().isEmpty()) {
            // ZXing does not currently support GS1 in Aztec Code symbols
            // ZXing does not currently support ECI in Aztec Code symbols
            // ZXing does not currently support reader initialization in Aztec Code symbols
            // ZXing cannot find symbols if they don't contain any actual data
            return new AztecReader();
        } else if (symbol instanceof QrCode) {
            return new QRCodeReader();
        } else if (symbol instanceof MaxiCode &&
                   ((MaxiCode) symbol).getMode() != 6 &&
                   ((MaxiCode) symbol).getEciMode() == 3 &&
                   ((MaxiCode) symbol).getStructuredAppendTotal() == 1) {
            // ZXing does not currently support mode 6 in MaxiCode symbols
            // ZXing does not currently support ECI in MaxiCode symbols
            // ZXing does not currently support Structured Append in MaxiCode symbols
            return new MaxiCodeReader();
        } else if (symbol instanceof DataMatrix &&
                   symbol.getEciMode() == 3 &&
                   ((DataMatrix) symbol).getStructuredAppendTotal() == 1) {
            // ZXing does not currently support ECI in Data Matrix symbols
            // ZXing does not currently support Structured Append in Data Matrix symbols
            return new DataMatrixReader();
        } else if (symbol instanceof Ean) {
            Ean ean = (Ean) symbol;
            if (ean.getMode() == Ean.Mode.EAN8) {
                return new EAN8Reader();
            } else {
                return new EAN13Reader();
            }
        } else if (symbol instanceof Pdf417) {
            Pdf417 pdf417 = (Pdf417) symbol;
            if (pdf417.getMode() != Pdf417.Mode.MICRO) {
                return new PDF417Reader();
            }
        } else if (symbol instanceof Upc) {
            Upc upc = (Upc) symbol;
            if (upc.getMode() == Upc.Mode.UPCA) {
                return new UPCAReader();
            } else {
                return new UPCEReader();
            }
        } else if (symbol instanceof DataBarExpanded && symbol.getDataType() == DataType.GS1) {
            return new RSSExpandedReader();
        } else if (symbol instanceof DataBar14 && !((DataBar14) symbol).getLinkageFlag()) {
            return new RSS14Reader();
        }

        // no corresponding ZXing reader exists, or it behaves badly so we don't use it for testing
        return null;
    }

    /**
     * Massages ZXing barcode reading results to make them easier to check against Okapi data.
     *
     * @param s the barcode content
     * @param symbol the symbol which encoded the content
     * @return the massaged barcode content
     */
    private static String massageZXingData(String s, Symbol symbol) {

        if (symbol instanceof Ean || symbol instanceof Upc) {
            // remove the checksum from the barcode content
            return s.substring(0, s.length() - 1);
        } else if (symbol instanceof DataMatrix &&
                   symbol.getDataType() == DataType.GS1) {
            // remove initial GS + transform subsequent GS -> '\<FNC1>' (Okapi representation of FNC1)
            return s.substring(1).replace("\u001d", Symbol.FNC1_STRING);
        } else if (symbol instanceof QrCode &&
                   symbol.getDataType() == DataType.GS1) {
            // transform GS -> '\<FNC1>' (Okapi representation of FNC1)
            return s.replace("\u001d", Symbol.FNC1_STRING);
        } else if (symbol instanceof AztecCode && ((AztecCode) symbol).getStructuredAppendTotal() > 1) {
            // remove first two characters, which actually represent the structured append position and total count information
            // also remove the message ID and the surrounding space characters, if there was a message ID
            String messageId = ((AztecCode) symbol).getStructuredAppendMessageId();
            int skip = 2 + (messageId != null ? messageId.length() + 2 : 0);
            return s.substring(skip);
        } else if (symbol instanceof DataBarExpanded) {
            // remove parenthesis around the GS1 AIs
            return s.replaceAll("\\((\\d+)\\)", "$1");
        } else if (symbol instanceof DataBar14) {
            // remove the checksum from the barcode content
            s = s.substring(0, s.length() - 1);
            // also remove left padding 0s (unless it's all zeroes)
            return s.matches("^0+$") ? s : s.replaceFirst("^0+", "");
        } else if (symbol instanceof Code2Of5) {
            Code2Of5 tof = (Code2Of5) symbol;
            if (tof.getMode() == Code2Of5.ToFMode.INTERLEAVED_WITH_CHECK_DIGIT || tof.getMode() == Code2Of5.ToFMode.ITF14) {
                // remove the checksum from the barcode content
                return s.substring(0, s.length() - 1);
            }
        }

        // no massaging
        return s;
    }

    /**
     * Massages Okapi barcode content to make it easier to check against ZXing output.
     *
     * @param s the barcode content
     * @param symbol the symbol which encoded the content
     * @return the massaged barcode content
     */
    private static String massageOkapiData(String s, Symbol symbol) {

        if (symbol instanceof Codabar) {
            // remove the start/stop characters from the specified barcode content
            return s.substring(1, s.length() - 1);
        } else if (symbol instanceof Code128) {
            // remove function characters, since ZXing mostly ignores them during read
            return s.replace(Symbol.FNC1_STRING, "")
                    .replace(Symbol.FNC2_STRING, "")
                    .replace(Symbol.FNC3_STRING, "")
                    .replace(Symbol.FNC4_STRING, "");
        } else if (symbol instanceof UspsPackage || symbol instanceof Nve18) {
            // remove AI brackets, since ZXing doesn't include them
            return s.replaceAll("[\\[\\]]", "");
        } else if (symbol instanceof DataBarExpanded) {
            // remove explicit FNC1s, since ZXing doesn't include them
            return s.replace(Symbol.FNC1_STRING, "");
        } else if (symbol instanceof MaxiCode) {
            MaxiCode maxicode = (MaxiCode) symbol;
            if (maxicode.getMode() == 2 || maxicode.getMode() == 3) {
                // combine the primary message and secondary data, since ZXing combines them in the decoding result
                String p = maxicode.getPrimary();
                String c = maxicode.getContent();
                char gs = '\u001D';
                int i = (maxicode.getMode() == 2 ? 9 : 6);
                return c.substring(0, 9) + p.substring(0, i) + gs + p.substring(9, 12) + gs + p.substring(12) + gs + c.substring(9);
            } else {
                return s;
            }
        } else if (symbol instanceof Code2Of5) {
            Code2Of5 tof = (Code2Of5) symbol;
            if (tof.getMode() == Code2Of5.ToFMode.INTERLEAVED && s.length() % 2 == 1) {
                // internally this would have been converted to an even number of digits
                return "0" + s;
            } else if (tof.getMode() == Code2Of5.ToFMode.INTERLEAVED_WITH_CHECK_DIGIT && s.length() % 2 == 0) {
                // internally this would have been converted to an even number of digits (odd without the check digit)
                return "0" + s;
            } else if (tof.getMode() == Code2Of5.ToFMode.ITF14) {
                // internally this would have been padded out to 14 digits (13 without the check digit)
                return String.format("%013d", Long.parseLong(s));
            }
        }

        // no massaging
        return s;
    }

    /**
     * Verifies that the metadata in the specified ZXing result matches the information in the specified symbol, if appropriate.
     *
     * @param symbol the symbol to check against
     * @param result the ZXing result to check
     */
    public static void verifyMetadata(Symbol symbol, Result result) {

        if (symbol instanceof Pdf417) {
            Pdf417 pdf417 = (Pdf417) symbol;
            if (pdf417.getStructuredAppendTotal() > 1) {
                // verify Macro PDF417 metadata
                PDF417ResultMetadata metadata = (PDF417ResultMetadata) result.getResultMetadata().get(ResultMetadataType.PDF417_EXTRA_METADATA);
                assertEquals(pdf417.getStructuredAppendFileId(), metadata.getFileId());
                assertEquals(pdf417.getStructuredAppendPosition() - 1, metadata.getSegmentIndex());
                assertEquals(pdf417.getStructuredAppendPosition() == pdf417.getStructuredAppendTotal(), metadata.isLastSegment());
                assertEquals(pdf417.getStructuredAppendFileName(), metadata.getFileName());
                assertEquals(pdf417.getStructuredAppendIncludeSegmentCount() ? pdf417.getStructuredAppendTotal() : -1, metadata.getSegmentCount());
            }
        }

        if (symbol instanceof Upc) {
            Upc upc = (Upc) symbol;
            if (upc.getAddOnContent() != null) {
                String extension = (String) result.getResultMetadata().get(ResultMetadataType.UPC_EAN_EXTENSION);
                assertEquals(upc.getAddOnContent(), extension);
            }
        }

        if (symbol instanceof Ean) {
            Ean ean = (Ean) symbol;
            if (ean.getAddOnContent() != null) {
                String extension = (String) result.getResultMetadata().get(ResultMetadataType.UPC_EAN_EXTENSION);
                assertEquals(ean.getAddOnContent(), extension);
            }
        }

        Integer errors = (Integer) result.getResultMetadata().get(ResultMetadataType.ERRORS_CORRECTED);
        if (errors != null) {
            if (symbol instanceof SwissQrCode) {
                assertNotEquals(0, errors);
            } else {
                assertEquals(0, errors);
            }
        }

        Integer erasures = (Integer) result.getResultMetadata().get(ResultMetadataType.ERASURES_CORRECTED);
        if (erasures != null) {
            assertEquals(0, erasures);
        }
    }

    /**
     * Verifies that the specified symbol encountered the expected error during encoding.
     *
     * @param config the test configuration, as read from the test properties file
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param actualError the actual exception
     */
    private static void verifyError(TestConfig config, File pngFile, Throwable actualError) {
        assertFalse(pngFile.exists());
        assertTrue(actualError instanceof OkapiInputException ||
                   actualError instanceof IllegalArgumentException);
        String actualErrorMessage = actualError.getMessage();
        if (config.expectedError != null && config.expectedError.startsWith("regex:")) {
            // treat error message as a regular expression
            String expected = config.expectedError.substring(6);
            assertTrue(actualErrorMessage.matches(expected), actualError + " <-> " + expected);
        } else {
            // treat error message literally
            assertEquals(config.expectedError, actualErrorMessage);
        }
    }

    /**
     * If necessary, generates the test expectations for the specified symbol.
     *
     * @param config the test configuration, as read from the test properties file
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param symbol the symbol to generate test expectations for
     * @param actualError the actual exception (may be <tt>null</tt> if there was no error)
     * @throws IOException if there is any I/O error
     */
    private static void addMissingExpectations(TestConfig config, File pngFile, Symbol symbol, Throwable actualError) throws IOException {

        // check the properties file on disk one more time before adding anything to it; otherwise,
        // files containing multiple test cases will generate multiple expectations sections when
        // we first auto-generate them

        byte[] bytes = Files.readAllBytes(config.file.toPath());
        String content = decode(bytes, UTF_8);
        boolean propertiesFileNeedsModification =
            !content.contains(EOL + ReadMode.CODEWORDS.name() + EOL) &&
            !content.contains(EOL + ReadMode.LOG.name() + EOL) &&
            !content.contains(EOL + ReadMode.ERROR.name() + EOL);

        if (actualError != null) {
            if (propertiesFileNeedsModification) {
                addExpectedError(config, actualError.getMessage());
            }
        } else {
            createExpectedPngFile(pngFile, symbol);
            if (propertiesFileNeedsModification) {
                addExpectedLog(config, symbol);
                addExpectedCodewords(config, symbol);
            }
        }
    }

    /**
     * Generates the error section for the specified symbol and adds it to the test properties file.
     *
     * @param config the test configuration, as read from the test properties file
     * @param error the error message
     * @throws IOException if there is any I/O error
     */
    private static void addExpectedError(TestConfig config, String error) throws IOException {
        if (config.expectedError == null) {
            String append = EOL + ReadMode.ERROR.name() + EOL + EOL + error + EOL;
            Files.write(config.file.toPath(), append.getBytes(UTF_8), StandardOpenOption.APPEND);
        }
    }

    /**
     * Generates the encoding log section for the specified symbol and adds it to the test properties file.
     *
     * @param config the test configuration, as read from the test properties file
     * @param symbol the symbol to generate the encoding log for
     * @throws IOException if there is any I/O error
     */
    private static void addExpectedLog(TestConfig config, Symbol symbol) throws IOException {
        String info = symbol.getEncodeInfo();
        if (config.expectedLog.isEmpty() && info != null && !info.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(EOL).append(ReadMode.LOG.name()).append(EOL).append(EOL);
            String[] logs = info.split("\n");
            for (String log : logs) {
                if (!log.isEmpty()) {
                    sb.append(log.trim()).append(EOL);
                }
            }
            Files.write(config.file.toPath(), sb.toString().getBytes(UTF_8), StandardOpenOption.APPEND);
        }
    }

    /**
     * Generates the codeword section for the specified symbol and adds it to the test properties file.
     *
     * @param config the test configuration, as read from the test properties file
     * @param symbol the symbol to generate codewords for
     * @throws IOException if there is any I/O error
     */
    private static void addExpectedCodewords(TestConfig config, Symbol symbol) throws IOException {
        if (config.expectedCodewords.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(EOL).append(ReadMode.CODEWORDS.name()).append(EOL).append(EOL);
            try {
                int[] codewords = symbol.getCodewords();
                for (int codeword : codewords) {
                    sb.append(codeword).append(EOL);
                }
            } catch (UnsupportedOperationException e) {
                for (String pattern : symbol.pattern) {
                    sb.append(pattern).append(EOL);
                }
            }
            Files.write(config.file.toPath(), sb.toString().getBytes(UTF_8), StandardOpenOption.APPEND);
        }
    }

    /**
     * Generates the image expectation file for the specified symbol.
     *
     * @param pngFile the file containing the expected final rendering of the bar code, if this test verifies successful behavior
     * @param symbol the symbol to draw
     * @throws IOException if there is any I/O error
     */
    private static void createExpectedPngFile(File pngFile, Symbol symbol) throws IOException {
        if (!pngFile.exists()) {
            BufferedImage img = draw(symbol);
            ImageIO.write(img, "png", pngFile);
        }
    }

    /**
     * Draws the specified symbol and returns the resultant image.
     *
     * @param symbol the symbol to draw
     * @return the resultant image
     */
    public static BufferedImage draw(Symbol symbol) {

        int magnification = 10;
        int width = symbol.getWidth() * magnification;
        int height = symbol.getHeight() * magnification;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();

        Java2DRenderer renderer = new Java2DRenderer(g2d, magnification, Color.WHITE, Color.BLACK);
        renderer.render(symbol);

        g2d.dispose();

        return img;
    }

    /**
     * Reads the barcode in the specified image using the specified ZXing reader.
     *
     * @param image the barcode image
     * @param reader the reader to use to read the barcode
     * @return the reading result
     * @throws ReaderException if any error occurs during reading
     */
    public static Result decode(BufferedImage image, Reader reader) throws ReaderException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Map< DecodeHintType, Object > hints = new HashMap<>();
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, ISO_8859_1.name()); // help QR Code reader
        return reader.decode(bitmap, hints);
    }

    /**
     * Initializes the specified symbol using the specified properties, where keys are attribute names and
     * values are attribute values.
     *
     * @param symbol the symbol to initialize
     * @param properties the attribute names and values to set
     * @throws ReflectiveOperationException if there is any reflection error
     */
    private static void setProperties(Symbol symbol, Map< String, String > properties) throws ReflectiveOperationException {
        boolean contentHasBeenSet = false;
        for (Map.Entry< String, String > entry : properties.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            // content should only be set once, and it should be the last property set
            if (contentHasBeenSet) {
                throw new OkapiInternalException("Test should set " + name + " before content has been set, not after");
            }
            // set each symbol property using the corresponding setter method
            String setterName = "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
            Method setter = getMethod(symbol.getClass(), setterName, byte[].class);
            assertNotNull(setter, "unable to find method " + setterName);
            Object setterValue = invoke(symbol, setter, value);
            // while we're here, eliminate some of the code coverage noise by checking the corresponding getter, if there is one
            if (!"content".equals(name)) {
                String getterName = "get" + setterName.substring(3);
                Method getter = getMethod(symbol.getClass(), getterName, null);
                if (getter == null) {
                    getterName = "is" + setterName.substring(3);
                    getter = getMethod(symbol.getClass(), getterName, null);
                }
                if (getter != null) {
                    Object getterValue = getter.invoke(symbol);
                    assertEquals(setterValue, getterValue);
                }
            } else {
                contentHasBeenSet = true;
            }
        }
    }

    /**
     * Returns the method with the specified name in the specified class, or <tt>null</tt> if the
     * specified method cannot be found.
     *
     * @param clazz the class to search in
     * @param name the name of the method to search for
     * @param ignore parameter type that should be ignored, if any
     * @return the method with the specified name in the specified class
     */
    private static Method getMethod(Class< ? > clazz, String name, Class< ? > ignore) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && (ignore == null || !ignore.equals(method.getParameterTypes()[0]))) {
                return method;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && (ignore == null || !ignore.equals(method.getParameterTypes()[0]))) {
                return method;
            }
        }
        return null;
    }

    /**
     * Invokes the specified method on the specified object with the specified parameter.
     *
     * @param object the object to invoke the method on
     * @param setter the method to invoke
     * @param parameter the parameter to pass to the method
     * @return the actual parameter value passed to the method
     * @throws ReflectiveOperationException if there is any reflection error
     * @throws IllegalArgumentException if the specified parameter is not valid
     */
    @SuppressWarnings("unchecked")
    private static < E extends Enum< E >> Object invoke(Object object, Method setter, Object parameter)
                    throws ReflectiveOperationException, IllegalArgumentException {

        Object paramValue;

        if (parameter == null) {
            paramValue = null;
        } else {
            Class< ? > paramType = setter.getParameterTypes()[0];
            if (String.class.equals(paramType)) {
                paramValue = parameter.toString();
            } else if (boolean.class.equals(paramType)) {
                paramValue = Boolean.valueOf(parameter.toString());
            } else if (int.class.equals(paramType)) {
                paramValue = Integer.parseInt(parameter.toString());
            } else if (double.class.equals(paramType)) {
                paramValue = Double.parseDouble(parameter.toString());
            } else if (Character.class.equals(paramType)) {
                paramValue = parameter.toString().charAt(0);
            } else if (paramType.isEnum()) {
                Class< E > e = (Class< E >) paramType;
                paramValue = Enum.valueOf(e, parameter.toString());
            } else {
                throw new RuntimeException("Unknown setter type: " + paramType);
            }
        }

        setter.invoke(object, paramValue);
        return paramValue;
    }

    /**
     * Returns all .properties files in the specified directory, or an empty array if none are found.
     *
     * @param dir the directory to search in
     * @return all .properties files in the specified directory, or an empty array if none are found
     */
    private static File[] getPropertiesFiles(String dir) {

        File[] files = new File(dir).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".properties");
            }
        });

        if (files != null) {
            return files;
        } else {
            return new File[0];
        }
    }

    /**
     * Verifies that the specified actual value matches the specified expected value, where the actual
     * value was generated using the specified inputs.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param inputs the inputs used to generate the actual value
     */
    public static void assertEqual(String expected, String actual, Object... inputs) {
        if (!Objects.equals(expected, actual)) {
            StringBuilder msg = new StringBuilder(100);
            msg.append('"').append(toPrintableAscii(expected)).append("\" != \"");
            msg.append(toPrintableAscii(actual)).append("\" for inputs (");
            for (int i = 0; i < inputs.length; i++) {
                Object input = inputs[i];
                if (input instanceof String s) {
                    msg.append('"').append(toPrintableAscii(s)).append('"');
                } else {
                    msg.append(input);
                }
                if (i + 1 < inputs.length) {
                    msg.append(", ");
                }
            }
            msg.append(")");
            throw new AssertionFailedError(msg.toString());
        }
    }

    /**
     * Verifies that the specified images match.
     *
     * @param expected the expected image to check against
     * @param actual the actual image
     * @param failureDirectory the directory to save images to if the assertion fails
     * @throws IOException if there is any I/O error
     */
    public static void assertEqual(BufferedImage expected, BufferedImage actual, String failureDirectoryName) throws IOException {

        int w = expected.getWidth();
        int h = expected.getHeight();
        File failureDirectory = new File(TEST_FAILURE_IMAGES_DIR, failureDirectoryName);

        if (w != actual.getWidth()) {
            writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
            throw new AssertionFailedError("image width", String.valueOf(w), String.valueOf(actual.getWidth()));
        }

        if (h != actual.getHeight()) {
            writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
            throw new AssertionFailedError("image height", String.valueOf(h), String.valueOf(actual.getHeight()));
        }

        DataBuffer expectedBuffer = expected.getRaster().getDataBuffer();
        DataBuffer actualBuffer = actual.getRaster().getDataBuffer();

        if (expectedBuffer instanceof DataBufferByte && actualBuffer instanceof DataBufferByte) {
            // optimize for the 99% case
            byte[] expectedData = ((DataBufferByte) expectedBuffer).getData();
            byte[] actualData = ((DataBufferByte) actualBuffer).getData();
            for (int i = 0; i < expectedData.length; i++) {
                byte expectedByte = expectedData[i];
                byte actualByte = actualData[i];
                if (expectedByte != actualByte) {
                    writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
                    throw new AssertionFailedError("byte " + i, toHexString(expectedByte), toHexString(actualByte));
                }
            }
        } else {
            // fall back for the 1% case
            int[] expectedPixels = new int[w * h];
            expected.getRGB(0, 0, w, h, expectedPixels, 0, w);
            int[] actualPixels = new int[w * h];
            actual.getRGB(0, 0, w, h, actualPixels, 0, w);
            for (int i = 0; i < expectedPixels.length; i++) {
                int expectedPixel = expectedPixels[i];
                int actualPixel = actualPixels[i];
                if (expectedPixel != actualPixel) {
                    writeImageFilesToFailureDirectory(expected, actual, failureDirectory);
                    throw new AssertionFailedError("pixel " + i, toHexString(expectedPixel), toHexString(actualPixel));
                }
            }
        }
    }

    private static void writeImageFilesToFailureDirectory(BufferedImage expected, BufferedImage actual, File failureDirectory)
        throws IOException {
        mkdirs(failureDirectory);
        ImageIO.write(actual, "png", new File(failureDirectory, "actual.png"));
        ImageIO.write(expected, "png", new File(failureDirectory, "expected.png"));
    }

    /**
     * Extracts test configuration properties from the specified properties file. A single properties file can contain
     * configuration properties for multiple tests, as long as the output expectations are identical.
     *
     * @param file the properties file to read
     * @return the tests in the specified file
     * @throws IOException if there is an error reading the properties file
     */
    private static List< TestConfig > readTestConfig(File file) throws IOException {

        String[] lines;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = decode(bytes, UTF_8);
            lines = content.split(EOL);
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 content in file " + file.getAbsolutePath(), e);
        }

        List< Map< String, String > > allProperties = new ArrayList<>();
        Map< String, String > properties = new LinkedHashMap<>();
        List< String > expectedCodewords = new ArrayList<>();
        List< String > expectedLog = new ArrayList<>();
        String expectedError = null;

        ReadMode mode = ReadMode.NONE;

        for (String line : lines) {
            if (line.startsWith("#")) {
                continue;
            } else if (ReadMode.PROPERTIES.name().equals(line)) {
                mode = ReadMode.PROPERTIES;
            } else if (ReadMode.CODEWORDS.name().equals(line)) {
                mode = ReadMode.CODEWORDS;
            } else if (ReadMode.LOG.name().equals(line)) {
                mode = ReadMode.LOG;
            } else if (ReadMode.ERROR.name().equals(line)) {
                mode = ReadMode.ERROR;
            } else {
                switch (mode) {
                    case LOG:
                        if (!line.isEmpty()) {
                            expectedLog.add(line);
                        }
                        break;
                    case ERROR:
                        if (!line.isEmpty()) {
                            expectedError = line;
                        }
                        break;
                    case CODEWORDS:
                        if (!line.isEmpty()) {
                            int index = line.indexOf("#");
                            if (index != -1) {
                                line = line.substring(0, index).trim();
                            }
                            expectedCodewords.add(line);
                        }
                        break;
                    case PROPERTIES:
                        if (line.isEmpty()) {
                            // an empty line signals the start of a new test configuration within this single file
                            if (!properties.isEmpty()) {
                                allProperties.add(properties);
                                properties = new LinkedHashMap<>();
                            }
                        } else {
                            int index = line.indexOf('=');
                            if (index != -1) {
                                String name = line.substring(0, index);
                                String value = Strings.unescape(line.substring(index + 1), true);
                                if ("null".equals(value)) {
                                    value = null;
                                }
                                properties.put(name, value);
                            } else {
                                String path = file.getAbsolutePath();
                                throw new IOException(path + ": found line '" + line + "' without '=' character; unintentional newline?");
                            }
                        }
                        break;
                    default:
                        // do nothing
                        break;
                }
            }
        }

        if (!properties.isEmpty()) {
            allProperties.add(properties);
        }

        List< TestConfig > configs = new ArrayList<>();
        for (Map< String, String > props : allProperties) {
            configs.add(new TestConfig(file, props, expectedCodewords, expectedLog, expectedError));
        }

        if (configs.isEmpty()) {
            throw new OkapiInternalException("Unable to find any tests in file " + file);
        }

        return configs;
    }

    /**
     * Equivalent to {@link String#String(byte[], Charset)}, except that encoding errors result in runtime errors instead of
     * silent character replacement.
     *
     * @param bytes the bytes to decode
     * @param charset the character set use to decode the bytes
     * @return the specified bytes, as a string
     * @throws CharacterCodingException if there is an error decoding the specified bytes
     */
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }

    /**
     * Finds all test resources and returns the information that JUnit needs to dynamically create the corresponding test cases.
     *
     * @return the test data needed to dynamically create the test cases
     * @throws IOException if there is an error reading a file
     */
    public static List< Object[] > data() throws IOException {

        clear(TEST_FAILURE_IMAGES_DIR);
        mkdirs(TEST_FAILURE_IMAGES_DIR);

        String symbolFilter = System.getProperty("okapi.test.symbol");
        String testNameFilter = System.getProperty("okapi.test.name");

        String backend = "uk.org.okapibarcode.backend";
        Reflections reflections = new Reflections(backend);
        Set< Class< ? extends Symbol >> symbols = reflections.getSubTypesOf(Symbol.class);

        List< Object[] > data = new ArrayList<>();
        for (Class< ? extends Symbol > symbol : symbols) {
            String symbolName = symbol.getSimpleName().toLowerCase();
            if (symbolFilter == null || symbolFilter.isEmpty() || symbolFilter.equalsIgnoreCase(symbolName)) {
                String dir = "src/test/resources/" + backend.replace('.', '/') + "/" + symbolName;
                for (File file : getPropertiesFiles(dir)) {
                    String fileBaseName = file.getName().replaceAll(".properties", "");
                    if (testNameFilter == null || testNameFilter.isEmpty() || testNameFilter.equalsIgnoreCase(fileBaseName)) {
                        File pngFile = new File(file.getParentFile(), fileBaseName + ".png");
                        for (TestConfig config : readTestConfig(file)) {
                            data.add(new Object[] { symbol, config, pngFile, symbolName, fileBaseName });
                        }
                    }
                }
            }
        }

        return data;
    }

    private static void clear(File dir) {
        if (dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    clear(file);
                }
                if (!file.delete()) {
                    throw new OkapiInternalException("Unable to delete file: " + file);
                }
            }
        }
    }

    private static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new OkapiInternalException("Unable to create directory: " + dir);
        }
    }

    private static final class TestConfig {

        public final File file;
        public final Map< String, String > properties;
        public final List< String > expectedCodewords;
        public final List< String > expectedLog;
        public final String expectedError;

        public TestConfig(File file,
                          Map< String, String > properties,
                          List< String > expectedCodewords,
                          List< String > expectedLog,
                          String expectedError) {
            this.file = file;
            this.properties = properties;
            this.expectedCodewords = expectedCodewords;
            this.expectedLog = expectedLog;
            this.expectedError = expectedError;
        }

        public boolean hasSuccessExpectations() {
            // don't check log, it is sometimes empty for success test cases for some symbol types
            return !expectedCodewords.isEmpty();
        }

        public boolean hasErrorExpectations() {
            return expectedError != null;
        }
    }

    private static enum ReadMode {
        PROPERTIES,
        CODEWORDS,
        LOG,
        ERROR,
        NONE
    }
}
