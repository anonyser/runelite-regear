package com.anonyser.regear;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.slf4j.LoggerFactory;

public class RegearPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Dev runs only: surface the plugin's log.debug lines into ~/.runelite/regear-dev.log so the
		// launcher's live-tail window (and a Monitor tail) can follow Regear the whole session. Which
		// slf4j backend answers depends on the test classpath -- RuneLite's logback or the slf4j-simple
		// test dependency -- so cover BOTH: set the simple-logger properties (used if simple wins, and
		// they must be set before the first logger is created), and, if logback answered, attach a file
		// appender to the com.anonyser logger pointed at the same file. A hard logback cast once killed
		// the dev client outright when simple won the binding, so everything logback is guarded by the
		// instanceof check and a best-effort try/catch.
		final String devLog = System.getProperty("user.home") + "/.runelite/regear-dev.log";
		System.setProperty("org.slf4j.simpleLogger.log.com.anonyser", "debug");
		System.setProperty("org.slf4j.simpleLogger.logFile", devLog);

		final org.slf4j.Logger logger = LoggerFactory.getLogger("com.anonyser");
		if (logger instanceof Logger)
		{
			final Logger lb = (Logger) logger;
			lb.setLevel(Level.DEBUG);
			try
			{
				final LoggerContext ctx = lb.getLoggerContext();
				final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
				encoder.setContext(ctx);
				encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n");
				encoder.start();
				final FileAppender<ILoggingEvent> appender = new FileAppender<>();
				appender.setContext(ctx);
				appender.setFile(devLog);
				appender.setAppend(true);
				appender.setEncoder(encoder);
				appender.start();
				lb.addAppender(appender);
				lb.setAdditive(true);
			}
			catch (Throwable t)
			{
				// Best-effort dev logging; never let it stop the client from launching.
			}
		}

		ExternalPluginManager.loadBuiltin(RegearPlugin.class);
		RuneLite.main(args);
	}
}
