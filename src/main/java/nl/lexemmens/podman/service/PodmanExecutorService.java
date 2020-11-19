package nl.lexemmens.podman.service;

import nl.lexemmens.podman.enumeration.PodmanCommand;
import nl.lexemmens.podman.enumeration.TlsVerify;
import nl.lexemmens.podman.executor.CommandExecutorDelegate;
import nl.lexemmens.podman.image.ImageConfiguration;
import nl.lexemmens.podman.image.PodmanConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static nl.lexemmens.podman.enumeration.TlsVerify.NOT_SPECIFIED;

/**
 * Class that allows very specific execution of Podman related commands.
 */
public class PodmanExecutorService {

    private static final String BUILD_FORMAT_CMD = "--format=";
    private static final String SAVE_FORMAT_CMD = "--format=oci-archive";
    private static final String OUTPUT_CMD = "--output";
    private static final String CONTAINERFILE_CMD = "--file=";
    private static final String NO_CACHE_CMD = "--no-cache=";
    private static final String PULL_CMD = "--pull=";
    private static final String ROOT_CMD = "--root=";
    private static final String RUNROOT_CMD = "--runroot=";

    private static final File BASE_DIR = new File(".");

    private final Log log;
    private final TlsVerify tlsVerify;
    private final CommandExecutorDelegate delegate;
    private final File podmanRoot;
    private final File podmanRunRoot;
    private final File podmanRunDirectory;


    /**
     * Constructs a new instance of this class.
     *
     * @param log          Used to access Maven's log system
     * @param podmanConfig Contains Podman specific configuration, such as tlsVerify and podman's root directory
     * @param delegate     A delegate executor that executed the actual command
     */
    public PodmanExecutorService(Log log, PodmanConfiguration podmanConfig, CommandExecutorDelegate delegate) {
        this.log = log;
        this.delegate = delegate;
        this.tlsVerify = podmanConfig.getTlsVerify();
        this.podmanRoot = podmanConfig.getRoot();
        this.podmanRunRoot = podmanConfig.getRunRoot();
        this.podmanRunDirectory = podmanConfig.getRunDirectory();
    }

    /**
     * <p>
     * Implementation of the 'podman build' command.
     * </p>
     * <p>
     * Takes an {@link ImageConfiguration} class as input and uses it to retrieve
     * the Containerfile to build, whether caching should be used and the build's output directory
     * </p>
     *
     * @param image The {@link ImageConfiguration} containing the configuration of the image to build
     * @return The last line of the build process, usually containing the image hash
     * @throws MojoExecutionException In case the container image could not be built.
     */
    public List<String> build(ImageConfiguration image) throws MojoExecutionException {
        List<String> subCommand = new ArrayList<>();
        subCommand.add(BUILD_FORMAT_CMD + image.getBuild().getFormat().getValue());
        subCommand.add(CONTAINERFILE_CMD + image.getBuild().getTargetContainerFile());
        subCommand.add(NO_CACHE_CMD + image.getBuild().isNoCache());
        subCommand.add(PULL_CMD + image.getBuild().isPull());
        subCommand.add(".");

        return runCommand(podmanRunDirectory, false, PodmanCommand.BUILD, subCommand);
    }

    /**
     * <p>
     * Implementation of the 'podman tag' command.
     * </p>
     *
     * @param imageHash     The image hash as generated by the {@link #build(ImageConfiguration)} method
     * @param fullImageName The full name of the image. This will be the target name
     * @throws MojoExecutionException In case the container image could not be tagged.
     */
    public void tag(String imageHash, String fullImageName) throws MojoExecutionException {
        // Ignore output
        runCommand(PodmanCommand.TAG, List.of(imageHash, fullImageName));
    }

    /**
     * <p>
     * Implementation of the 'podman save' command.
     * </p>
     * <p>
     * Note: This is not an export. The result of the save command is a tar ball containing all layers
     * as separate folders
     * </p>
     *
     * @param archiveName   The target name of the archive, where the image will be saved into.
     * @param fullImageName The image to save
     * @throws MojoExecutionException In case the container image could not be saved.
     */
    public void save(String archiveName, String fullImageName) throws MojoExecutionException {
        List<String> subCommand = new ArrayList<>();
        subCommand.add(SAVE_FORMAT_CMD);
        subCommand.add(OUTPUT_CMD);
        subCommand.add(archiveName);
        subCommand.add(fullImageName);

        runCommand(PodmanCommand.SAVE, subCommand);
    }

    /**
     * <p>
     * Implementation of the 'podman push' command.
     * </p>
     *
     * @param fullImageName The full name of the image including the registry
     * @throws MojoExecutionException In case the container image could not be pushed.
     */
    public void push(String fullImageName) throws MojoExecutionException {
        // Apparently, actually pushing the blobs to a registry causes some output on stderr.
        // Ignore output
        runCommand(BASE_DIR, false, PodmanCommand.PUSH, List.of(fullImageName));
    }

    /**
     * <p>
     * Implementation of the 'podman login' command.
     * </p>
     * <p>
     * This command is used to login to a specific registry with a specific username and password
     * </p>
     *
     * @param registry The registry to logon to
     * @param username The username to use
     * @param password The password to use
     * @throws MojoExecutionException In case the login fails. The Exception does not contain a recognisable password.
     */
    public void login(String registry, String username, String password) throws MojoExecutionException {
        List<String> subCommand = new ArrayList<>();
        subCommand.add(registry);
        subCommand.add("-u");
        subCommand.add(username);
        subCommand.add("-p");
        subCommand.add(password);

        try {
            runCommand(PodmanCommand.LOGIN, subCommand);
        } catch (MojoExecutionException e) {
            // When the command fails, the whole command is put in the error message, possibly exposing passwords.
            // Therefore we catch the exception, remove the password and throw a new exception with an updated message.
            String message = e.getMessage().replaceAll(String.format("-p[, ]+%s", Pattern.quote(password)), "-p **********");
            log.error(message);
            throw new MojoExecutionException(message);
        }
    }

    /**
     * <p>
     * Implementation of the 'podman version' command
     * </p>
     *
     * @throws MojoExecutionException In case printing the information fails
     */
    public void version() throws MojoExecutionException {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(PodmanCommand.PODMAN.getCommand());
        fullCommand.add(PodmanCommand.VERSION.getCommand());

        runCommand(fullCommand, BASE_DIR, true);
    }

    /**
     * <p>
     * Implementation of the 'podman rmi' command.
     * </p>
     *
     * <p>
     * Removes an image from the local registry
     * </p>
     *
     * @param fullImageName The full name of the image to remove from the local registry
     * @throws MojoExecutionException In case the container image could not be removed.
     */
    public void removeLocalImage(String fullImageName) throws MojoExecutionException {
        runCommand(PodmanCommand.RMI, List.of(fullImageName));
    }

    private List<String> decorateCommands(PodmanCommand podmanCommand, List<String> subCommands) {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(PodmanCommand.PODMAN.getCommand());

        // Path to the root directory in which data, including images, is stored. Must be *before* build, push or any other operation
        if (podmanRoot != null) {
            fullCommand.add(ROOT_CMD + podmanRoot.getAbsolutePath());
        }

        if (podmanRunRoot != null) {
            fullCommand.add(RUNROOT_CMD + podmanRunRoot.getAbsolutePath());
        }

        fullCommand.add(podmanCommand.getCommand());

        if (isTlsSupported(podmanCommand) && tlsVerify != null && !NOT_SPECIFIED.equals(tlsVerify)) {
            fullCommand.add(tlsVerify.getCommand());
        }

        fullCommand.addAll(subCommands);
        return fullCommand;
    }

    private boolean isTlsSupported(PodmanCommand podmanCommand) {
        return !PodmanCommand.TAG.equals(podmanCommand)
                && !PodmanCommand.SAVE.equals(podmanCommand)
                && !PodmanCommand.RMI.equals(podmanCommand);
    }

    private List<String> runCommand(File workDir, boolean redirectError, PodmanCommand command, List<String> subCommands) throws MojoExecutionException {
        List<String> fullCommand = decorateCommands(command, subCommands);
        return runCommand(fullCommand, workDir, redirectError);
    }

    private void runCommand(PodmanCommand command, List<String> subCommands) throws MojoExecutionException {
        // Ignore output
        runCommand(BASE_DIR, true, command, subCommands);
    }

    private List<String> runCommand(List<String> fullCommand, File workDir, boolean redirectError) throws MojoExecutionException {
        String msg = String.format("Executing command '%s' from basedir %s", StringUtils.join(fullCommand, " "), BASE_DIR.getAbsolutePath());
        log.debug(msg);
        ProcessExecutor processExecutor = new ProcessExecutor()
                .directory(workDir)
                .command(fullCommand)
                .readOutput(true)
                .redirectOutput(Slf4jStream.of(getClass().getSimpleName()).asInfo())
                .exitValueNormal();

        // Some processes print regular text on stderror, so make redirecting the error stream configurable.
        if (redirectError) {
            processExecutor.redirectError(Slf4jStream.of(getClass().getSimpleName()).asError());
        }

        return delegate.executeCommand(processExecutor);
    }
}
