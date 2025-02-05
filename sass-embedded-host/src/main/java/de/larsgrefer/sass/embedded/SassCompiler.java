package de.larsgrefer.sass.embedded;

import com.google.protobuf.ByteString;
import de.larsgrefer.sass.embedded.connection.CompilerConnection;
import de.larsgrefer.sass.embedded.functions.HostFunction;
import de.larsgrefer.sass.embedded.importer.CustomImporter;
import de.larsgrefer.sass.embedded.importer.FileImporter;
import de.larsgrefer.sass.embedded.importer.Importer;
import de.larsgrefer.sass.embedded.importer.RelativeUrlImporter;
import de.larsgrefer.sass.embedded.logging.LoggingHandler;
import de.larsgrefer.sass.embedded.logging.Slf4jLoggingHandler;
import de.larsgrefer.sass.embedded.util.SyntaxUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.intellij.lang.annotations.Language;
import sass.embedded_protocol.EmbeddedSass;
import sass.embedded_protocol.EmbeddedSass.InboundMessage;
import sass.embedded_protocol.EmbeddedSass.InboundMessage.*;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage.CanonicalizeRequest;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage.CompileResponse.CompileSuccess;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage.FileImportRequest;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage.FunctionCallRequest;
import sass.embedded_protocol.EmbeddedSass.OutboundMessage.ImportRequest;
import sass.embedded_protocol.EmbeddedSass.OutputStyle;
import sass.embedded_protocol.EmbeddedSass.Syntax;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.URL;
import java.util.*;

import static de.larsgrefer.sass.embedded.util.ProtocolUtil.inboundMessage;

/**
 * @author Lars Grefer
 * @see SassCompilerFactory#bundled()
 */
@Slf4j
public class SassCompiler implements Closeable {

    /**
     * How to format the CSS output.
     *
     * @see CompileRequest#getStyle()
     */
    @Getter
    @Setter
    private OutputStyle outputStyle = OutputStyle.EXPANDED;

    /**
     * Whether to generate a source map. Note that this will *not* add a source
     * map comment to the stylesheet; that's up to the host or its users.
     *
     * @see CompileRequest#getSourceMap()
     */
    @Getter
    @Setter
    private boolean generateSourceMaps = false;

    /**
     * Whether to include sources in the generated sourcemap
     *
     * @see CompileRequest#getSourceMapIncludeSources()
     */
    @Getter
    @Setter
    private boolean sourceMapIncludeSources = false;

    /**
     * Whether to report all deprecation warnings or only the first few ones.
     * If this is `false`, the compiler may choose not to send events for
     * repeated deprecation warnings. If this is `true`, the compiler must emit
     * an event for every deprecation warning it encounters.
     *
     * @see CompileRequest#getVerbose()
     */
    @Getter
    @Setter
    private boolean verbose = false;

    private final CompilerConnection connection;

    private final Random compileRequestIds = new Random();

    private final Map<String, HostFunction> globalFunctions = new HashMap<>();
    private final Map<Integer, FileImporter> fileImporters = new HashMap<>();
    private final Map<Integer, CustomImporter> customImporters = new HashMap<>();

    @Setter
    @Getter
    private LoggingHandler loggingHandler = new Slf4jLoggingHandler(log);

    @Getter
    @Setter
    private List<File> loadPaths = new LinkedList<>();

    public SassCompiler(CompilerConnection connection) {
        this.connection = connection;
    }

    public OutboundMessage.VersionResponse getVersion() throws IOException {
        return exec(inboundMessage(VersionRequest.getDefaultInstance())).getVersionResponse();
    }

    public void registerFunction(@NonNull HostFunction sassFunction) {
        globalFunctions.put(sassFunction.getName(), sassFunction);
    }

    public void registerImporter(@NonNull FileImporter fileImporter) {
        fileImporters.put(fileImporter.getId(), fileImporter);
    }

    public void registerImporter(@NonNull CustomImporter customImporter) {
        customImporters.put(customImporter.getId(), customImporter);
    }

    protected CompileRequest.Builder compileRequestBuilder() {
        CompileRequest.Builder builder = CompileRequest.newBuilder();

        builder.setId(Math.abs(compileRequestIds.nextInt()));
        builder.setStyle(outputStyle);
        builder.setSourceMap(generateSourceMaps);
        builder.setSourceMapIncludeSources(sourceMapIncludeSources);
        builder.setVerbose(verbose);

        for (File loadPath : loadPaths) {
            CompileRequest.Importer importer = CompileRequest.Importer.newBuilder()
                    .setPath(loadPath.getAbsolutePath())
                    .build();
            builder.addImporters(importer);
        }

        for (Importer value : customImporters.values()) {
            CompileRequest.Importer importer = CompileRequest.Importer.newBuilder()
                    .setImporterId(value.getId())
                    .build();
            builder.addImporters(importer);
        }

        for (Importer value : fileImporters.values()) {
            CompileRequest.Importer importer = CompileRequest.Importer.newBuilder()
                    .setFileImporterId(value.getId())
                    .build();
            builder.addImporters(importer);
        }

        for (HostFunction sassFunction : globalFunctions.values()) {
            builder.addGlobalFunctions(sassFunction.getSignature());
        }

        return builder;
    }

    public CompileSuccess compile(@NonNull URL source) throws SassCompilationFailedException, IOException {
        return compile(source, getOutputStyle());
    }

    public CompileSuccess compile(@NonNull URL source, OutputStyle outputStyle) throws SassCompilationFailedException, IOException {
        if (source.getProtocol().equals("file")) {
            File file = new File(source.getPath());
            return compileFile(file);
        }

        Syntax syntax = SyntaxUtil.guessSyntax(source);
        ByteString content;
        try (InputStream in = source.openStream()) {
            content = ByteString.readFrom(in);
        }

        CustomImporter importer = new RelativeUrlImporter(source).autoCanonicalize();

        customImporters.put(importer.getId(), importer);

        CompileRequest.StringInput build = CompileRequest.StringInput.newBuilder()
                .setUrl(source.toString())
                .setSourceBytes(content)
                .setImporter(CompileRequest.Importer.newBuilder()
                        .setImporterId(importer.getId())
                        .build())
                .setSyntax(syntax)
                .build();

        try {
            return compileString(build, outputStyle);
        } finally {
            customImporters.remove(importer.getId());
        }
    }

    //region compileString and overloads
    public CompileSuccess compileScssString(@NonNull @Language("SCSS") String source) throws IOException, SassCompilationFailedException {
        return compileString(source, Syntax.SCSS);
    }

    public CompileSuccess compileSassString(@NonNull @Language("SASS") String source) throws IOException, SassCompilationFailedException {
        return compileString(source, Syntax.INDENTED);
    }

    public CompileSuccess compileCssString(@NonNull @Language("CSS") String source) throws IOException, SassCompilationFailedException {
        return compileString(source, Syntax.CSS);
    }

    public CompileSuccess compileString(String source, Syntax syntax) throws SassCompilationFailedException, IOException {
        return compileString(source, syntax, getOutputStyle());
    }

    public CompileSuccess compileString(@NonNull String source, Syntax syntax, OutputStyle outputStyle) throws IOException, SassCompilationFailedException {
        CompileRequest.StringInput stringInput = CompileRequest.StringInput.newBuilder()
                .setSource(source)
                .setSyntax(syntax)
                .build();

        return compileString(stringInput, outputStyle);
    }

    @Nonnull
    public CompileSuccess compileString(CompileRequest.StringInput string, @NonNull OutputStyle outputStyle) throws IOException, SassCompilationFailedException {

        CompileRequest compileRequest = compileRequestBuilder()
                .setString(string)
                .setStyle(outputStyle)
                .setId(new Random().nextInt())
                .build();

        return execCompileRequest(compileRequest);
    }
    //endregion

    //region compileFile

    public CompileSuccess compileFile(@NonNull File inputFile) throws IOException, SassCompilationFailedException {
        return compileFile(inputFile, getOutputStyle());
    }

    public CompileSuccess compileFile(@NonNull File file, @NonNull OutputStyle outputStyle) throws IOException, SassCompilationFailedException {
        CompileRequest compileRequest = compileRequestBuilder()
                .setPath(file.getPath())
                .setStyle(outputStyle)
                .build();

        return execCompileRequest(compileRequest);
    }

    //endregion

    private CompileSuccess execCompileRequest(CompileRequest compileRequest) throws IOException, SassCompilationFailedException {

        OutboundMessage outboundMessage = exec(inboundMessage(compileRequest));

        if (!outboundMessage.hasCompileResponse()) {
            throw new IllegalStateException("No compile response");
        }

        OutboundMessage.CompileResponse compileResponse = outboundMessage.getCompileResponse();

        if (compileResponse.getId() != compileRequest.getId()) {
            //Should never happen
            throw new IllegalStateException(String.format("Compilation ID mismatch: expected %d, but got %d", compileRequest.getId(), compileResponse.getId()));
        }

        if (compileResponse.hasSuccess()) {
            return compileResponse.getSuccess();
        } else if (compileResponse.hasFailure()) {
            throw new SassCompilationFailedException(compileResponse.getFailure());
        } else {
            throw new IllegalStateException("Neither success nor failure");
        }
    }

    private OutboundMessage exec(InboundMessage inboundMessage) throws IOException {
        synchronized (connection) {
            connection.sendMessage(inboundMessage);

            while (true) {
                OutboundMessage outboundMessage = connection.readResponse();

                switch (outboundMessage.getMessageCase()) {

                    case ERROR:
                        throw new SassProtocolErrorException(outboundMessage.getError());
                    case COMPILE_RESPONSE:
                    case VERSION_RESPONSE:
                        return outboundMessage;
                    case LOG_EVENT:
                        loggingHandler.handle(outboundMessage.getLogEvent());
                        break;
                    case CANONICALIZE_REQUEST:
                        handleCanonicalizeRequest(outboundMessage.getCanonicalizeRequest());
                        break;
                    case IMPORT_REQUEST:
                        handleImportRequest(outboundMessage.getImportRequest());
                        break;
                    case FILE_IMPORT_REQUEST:
                        handleFileImportRequest(outboundMessage.getFileImportRequest());
                        break;
                    case FUNCTION_CALL_REQUEST:
                        handleFunctionCallRequest(outboundMessage.getFunctionCallRequest());
                        break;
                    case MESSAGE_NOT_SET:
                        throw new IllegalStateException("No message set");
                    default:
                        throw new IllegalStateException("Unknown OutboundMessage: " + outboundMessage.getMessageCase());
                }
            }
        }
    }

    private void handleFileImportRequest(FileImportRequest fileImportRequest) throws IOException {
        FileImportResponse.Builder fileImportResponse = FileImportResponse.newBuilder()
                .setId(fileImportRequest.getId());

        FileImporter fileImporter = fileImporters.get(fileImportRequest.getImporterId());

        try {
            File file = fileImporter.handleImport(fileImportRequest.getUrl(), fileImportRequest.getFromImport());
            if (file != null) {
                fileImportResponse.setFileUrl(file.toURI().toURL().toString());
            }
        } catch (Throwable t) {
            log.debug("Failed to execute FileImportRequest {}", fileImportRequest, t);
            fileImportResponse.setError(getErrorMessage(t));
        }

        connection.sendMessage(inboundMessage(fileImportResponse.build()));
    }

    private void handleImportRequest(ImportRequest importRequest) throws IOException {
        ImportResponse.Builder importResponse = ImportResponse.newBuilder()
                .setId(importRequest.getId());

        CustomImporter customImporter = customImporters.get(importRequest.getImporterId());

        try {
            ImportResponse.ImportSuccess success = customImporter.handleImport(importRequest.getUrl());
            if (success != null) {
                importResponse.setSuccess(success);
            }
        } catch (Throwable t) {
            log.debug("Failed to handle ImportRequest {}", importRequest, t);
            importResponse.setError(getErrorMessage(t));
        }

        connection.sendMessage(inboundMessage(importResponse.build()));
    }

    private void handleCanonicalizeRequest(CanonicalizeRequest canonicalizeRequest) throws IOException {
        CanonicalizeResponse.Builder canonicalizeResponse = CanonicalizeResponse.newBuilder()
                .setId(canonicalizeRequest.getId());

        CustomImporter customImporter = customImporters.get(canonicalizeRequest.getImporterId());

        try {
            String canonicalize = customImporter.canonicalize(canonicalizeRequest.getUrl(), canonicalizeRequest.getFromImport());
            if (canonicalize != null) {
                canonicalizeResponse.setUrl(canonicalize);
            }
        } catch (Throwable e) {
            log.debug("Failed to handle CanonicalizeRequest {}", canonicalizeRequest, e);
            canonicalizeResponse.setError(getErrorMessage(e));
        }

        connection.sendMessage(inboundMessage(canonicalizeResponse.build()));
    }

    private void handleFunctionCallRequest(FunctionCallRequest functionCallRequest) throws IOException {
        FunctionCallResponse.Builder response = FunctionCallResponse.newBuilder()
                .setId(functionCallRequest.getId());

        HostFunction sassFunction = null;
        try {
            switch (functionCallRequest.getIdentifierCase()) {
                case NAME:
                    sassFunction = globalFunctions.get(functionCallRequest.getName());
                    break;
                case FUNCTION_ID:
                    throw new UnsupportedOperationException("Calling functions by ID is not supported");
                case IDENTIFIER_NOT_SET:
                    throw new IllegalArgumentException("FunctionCallRequest has no identifier");
            }

            List<EmbeddedSass.Value> argumentsList = functionCallRequest.getArgumentsList();
            EmbeddedSass.Value result = sassFunction.invoke(argumentsList);
            response.setSuccess(result);
        } catch (Throwable e) {
            log.debug("Failed to handle FunctionCallRequest for function {}", sassFunction, e);
            response.setError(getErrorMessage(e));
        }

        connection.sendMessage(inboundMessage(response.build()));
    }

    private String getErrorMessage(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
