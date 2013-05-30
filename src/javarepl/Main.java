package javarepl;

import com.googlecode.totallylazy.Function1;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.predicates.LogicalPredicate;
import javarepl.completion.Completer;
import javarepl.completion.CompletionResult;
import javarepl.completion.ConsoleCompleter;
import javarepl.console.Console;
import javarepl.console.*;
import javarepl.console.commands.Commands;
import javarepl.console.rest.RestConsole;
import jline.console.ConsoleReader;
import jline.console.completer.AggregateCompleter;
import jline.console.history.MemoryHistory;

import java.io.*;
import java.lang.management.ManagementPermission;
import java.lang.reflect.ReflectPermission;
import java.net.SocketPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.util.List;
import java.util.PropertyPermission;

import static com.googlecode.totallylazy.Callables.compose;
import static com.googlecode.totallylazy.Files.temporaryDirectory;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Predicates.notNullValue;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.replaceAll;
import static com.googlecode.totallylazy.Strings.startsWith;
import static com.googlecode.totallylazy.numbers.Numbers.intValue;
import static com.googlecode.totallylazy.numbers.Numbers.valueOf;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static javarepl.Utils.applicationVersion;
import static javarepl.Utils.randomServerPort;
import static javarepl.console.ConsoleConfig.consoleConfig;
import static javarepl.console.ConsoleLog.Type.ERROR;
import static javarepl.console.ConsoleLog.Type.SUCCESS;
import static javarepl.console.commands.Command.functions.completer;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

public class Main {
    private static PrintStream outStream = System.out;
    private static PrintStream errStream = System.err;

    public static void main(String... args) throws Exception {
        ConsoleLogger logger = systemStreamsLogger(args, isColored(args));
        ConsoleConfig consoleConfig = consoleConfig()
                .historyFile(historyFile(args))
                .expressions(initialExpressions(args))
                .logger(logger);

        RestConsole console = new RestConsole(new TimingOutConsole(new SimpleConsole(consoleConfig), expressionTimeout(args), inactivityTimeout(args)), port(args));

        ExpressionReader expressionReader = expressionReader(args, console);

        if (isSandboxed(args)) {
            sandboxApplication();
        }

        if (!ignoreConsole(args)) {
            logger.info("");
            logger.info(format("Welcome to JavaREPL version %s (%s, %s, Java %s)",
                    applicationVersion(),
                    isSandboxed(args) ? "sandboxed" : "unrestricted",
                    getProperty("java.vm.name"),
                    getProperty("java.version")));
        }

        if (getSystemJavaCompiler() == null) {
            logger.error("\nERROR: Java compiler not found.\n" +
                    "This can occur when JavaREPL was run with JRE instead of JDK or JDK is not configured correctly.");
            return;
        }

        if (!ignoreConsole(args)) {
            logger.info("Access local web console at http://localhost:" + console.port());
            logger.info("Type in expression to evaluate or :help for more options.");
            logger.info("");
        }

        for (String expression : consoleConfig.expressions) {
            console.execute(expression);
        }

        do {
            console.execute(expressionReader.readExpression().getOrNull());
            logger.info("");
        } while (true);

    }

    private static String[] initialExpressions(String[] args) {
        return sequence(args)
                .find(startsWith("--expression="))
                .map(replaceAll("--expression=", ""))
                .toSequence()
                .toArray(String.class);
    }

    private static ConsoleLogger systemStreamsLogger(String[] args, Boolean colored) {
        ConsoleLogger logger = new ConsoleLogger(outStream, errStream, colored);

        LogicalPredicate<String> ignoredLogs = startsWith("POST /")
                .or(startsWith("GET /"))
                .or(startsWith("Listening on http://"));

        System.setOut(new ConsoleLoggerPrintStream(SUCCESS, ignoredLogs, logger));
        System.setErr(new ConsoleLoggerPrintStream(ERROR, ignoredLogs, logger));

        return logger;
    }

    private static Option<File> historyFile(String[] args) {
        return isSandboxed(args)
                ? Option.<File>none()
                : some(new File(getProperty("user.home"), ".javarepl.history"));
    }

    private static ExpressionReader expressionReader(String[] args, Console console) throws IOException {
        if (simpleConsole(args))
            return new ExpressionReader(readFromSimpleConsole());

        if (ignoreConsole(args))
            return new ExpressionReader(ignoreConsoleInput());

        return new ExpressionReader(readFromExtendedConsole(console));
    }

    private static boolean simpleConsole(String[] args) {
        return sequence(args).contains("--simpleConsole");
    }

    private static boolean ignoreConsole(String[] args) {
        return sequence(args).contains("--ignoreConsole");
    }

    private static boolean isColored(String[] args) {
        return !simpleConsole(args) && !ignoreConsole(args);
    }

    private static boolean isSandboxed(String[] args) {
        return sequence(args).contains("--sandboxed");
    }

    private static Integer port(String[] args) {
        return sequence(args).find(startsWith("--port=")).map(compose(replaceAll("--port=", ""), compose(valueOf, intValue))).getOrElse(randomServerPort());
    }

    private static Option<Integer> expressionTimeout(String[] args) {
        return sequence(args).find(startsWith("--expressionTimeout=")).map(compose(replaceAll("--expressionTimeout=", ""), compose(valueOf, intValue)));
    }

    private static Option<Integer> inactivityTimeout(String[] args) {
        return sequence(args).find(startsWith("--inactivityTimeout=")).map(compose(replaceAll("--inactivityTimeout=", ""), compose(valueOf, intValue)));
    }

    private static void sandboxApplication() {
        Policy.setPolicy(new Policy() {
            private final PermissionCollection permissions = new Permissions();

            {
                permissions.add(new SocketPermission("*", "accept, connect, resolve"));
                permissions.add(new RuntimePermission("accessClassInPackage.sun.misc.*"));
                permissions.add(new RuntimePermission("accessClassInPackage.sun.misc"));
                permissions.add(new RuntimePermission("getProtectionDomain"));
                permissions.add(new RuntimePermission("accessDeclaredMembers"));
                permissions.add(new RuntimePermission("createClassLoader"));
                permissions.add(new RuntimePermission("closeClassLoader"));
                permissions.add(new RuntimePermission("modifyThreadGroup"));
                permissions.add(new RuntimePermission("getStackTrace"));
                permissions.add(new ManagementPermission("monitor"));
                permissions.add(new ReflectPermission("suppressAccessChecks"));
                permissions.add(new PropertyPermission("*", "read"));
                permissions.add(new FilePermission(temporaryDirectory("JavaREPL").getAbsolutePath() + "/-", "read, write, delete"));
                permissions.add(new FilePermission("<<ALL FILES>>", "read"));
            }

            @Override
            public PermissionCollection getPermissions(CodeSource codesource) {
                return permissions;
            }
        });

        System.setSecurityManager(new SecurityManager());
    }

    private static Function1<Sequence<String>, String> readFromExtendedConsole(final Console console) throws IOException {
        return new Function1<Sequence<String>, String>() {
            private final ConsoleReader consoleReader;

            {
                consoleReader = new ConsoleReader(System.in, outStream);
                consoleReader.setHistoryEnabled(true);
                consoleReader.setExpandEvents(false);
                consoleReader.addCompleter(new AggregateCompleter(completers().toList()));
            }

            private Sequence<jline.console.completer.Completer> completers() {
                return console.context().get(Commands.class).allCommands().map(completer()).filter(notNullValue())
                        .add(completerFor(new ConsoleCompleter(console)));
            }

            private jline.console.completer.Completer completerFor(final Completer completer) {
                return new jline.console.completer.Completer() {
                    public int complete(String expression, int cursor, List<CharSequence> candidates) {
                        CompletionResult result = completer.apply(expression);
                        if (expression.trim().startsWith(":") || result.candidates().isEmpty()) {
                            return -1;
                        } else {
                            candidates.addAll(result.candidates().toList());
                            return result.position();
                        }
                    }
                };
            }

            public String call(Sequence<String> lines) throws Exception {
                consoleReader.setPrompt(lines.isEmpty() ? "\u001B[1mjava> \u001B[0m" : "    \u001B[1m| \u001B[0m");
                consoleReader.setHistory(historyFromConsole());
                return consoleReader.readLine();
            }

            private MemoryHistory historyFromConsole() {
                MemoryHistory history = new MemoryHistory();
                for (String historyItem : console.context().get(ConsoleHistory.class).items()) {
                    history.add(historyItem);
                }
                return history;
            }
        };
    }

    private static Function1<Sequence<String>, String> readFromSimpleConsole() {
        return new Function1<Sequence<String>, String>() {
            private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

            public String call(Sequence<String> lines) throws Exception {
                return reader.readLine();
            }
        };
    }

    private static Function1<Sequence<String>, String> ignoreConsoleInput() {
        return new Function1<Sequence<String>, String>() {
            public String call(Sequence<String> strings) throws Exception {
                while (true) {
                    Thread.sleep(100);
                }
            }
        };
    }
}
