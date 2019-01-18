package de.jlo.talend.logaggregator;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.xml.DOMConfigurator;
import org.graylog2.log.GelfAppender;


/**
 * Hello world!
 *
 */
public class LogAgg {
	
	private String log4jConfigFile = null;
	private ReadPipeThread reader = null;
	private AggregateAndWriteLogsThread writer = null;
	private int queueSize = 100000;
	private BlockingDeque<String> dequeue = new LinkedBlockingDeque<String>(queueSize);
	private long maxTimeBetweenLinesOfAMessage = 100l;
	private long maxTimeToKeepAMessage = 2000l;
	private int maxMessageSize = 2048;
	private int incomingBufferSize = 8192;
	private static final String THE_END = "STOP_LOGGING";
	private Logger logger = null;
	private Exception readerException = null;
	private Exception writerException = null;
	private String jobName = null;
	private String graylogHost = null;
	private Date applicationStartTime = new Date();
	
    public static void main(String[] args) {
    	LogAgg la = new LogAgg();
    	la.configure(args);
    	try {
        	la.initLog4J();
    	} catch (Exception e) {
    		e.printStackTrace();
    		System.exit(3);
    	}
    	la.start();
    	la.waitUntilEnd();
    	la.exit();
    }
    
    public void configure(String[] args) {
    	Options options = new Options();
    	options.addOption("j", "jobname", true, "Job name");
    	options.addOption("t", "application_name", true, "Job name (compatible to logger)");
    	options.addOption("c", "config_file", true, "Log4j config file");
    	options.addOption("g", "graylog_host", true, "Graylog host");
    	options.addOption("q", "queue_size", true, "Message queue size");
    	options.addOption("s", "max_message_size", true, "Max message size");
    	options.addOption("x", "max_time_between_lines", true, "Max time between lines to get them as one message");
    	options.addOption("y", "max_time_until_send", true, "Max time to collect data until a new message will be send");
    	CommandLineParser parser = new DefaultParser();
    	try {
			String message = null;
    		CommandLine cmd = parser.parse( options, args);
			jobName = cmd.getOptionValue('j');
			if (jobName == null) {
				jobName = cmd.getOptionValue('t');
			}
			if (jobName == null) {
				message = "Parameter jobname or application_name must be set.";
			}
			log4jConfigFile = cmd.getOptionValue('c');
			String mtbl_string = cmd.getOptionValue('x');
			try {
				if (mtbl_string != null && mtbl_string.trim().isEmpty() == false) {
					setMaxTimeBetweenLinesOfAMessage(Long.valueOf(mtbl_string));
				}
			} catch (Exception e1) {
				message = "Parameter x must be an long value.";
			}
			String mtuns_string = cmd.getOptionValue('y');
			try {
				if (mtuns_string != null && mtuns_string.trim().isEmpty() == false) {
					setMaxTimeToKeepAMessage(Long.valueOf(mtuns_string));
				}
			} catch (Exception e1) {
				message = "Parameter y must be an long value.";
			}
			String size = cmd.getOptionValue('s');
			try {
				if (size != null && size.trim().isEmpty() == false) {
					setMaxMessageSize(Integer.valueOf(size));
				}
			} catch (Exception e1) {
				message = "Parameter s must be an integer value.";
			}
			if (message != null) {
				System.out.println(message);
				HelpFormatter formatter = new HelpFormatter();
				formatter.printHelp("java -jar log-aggregator-1.0.jar", options);
				System.exit(4);
			}
			graylogHost = cmd.getOptionValue('g');
		} catch (Exception e) {
			System.err.println(e.getMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("java -jar log-aggregator-1.0.jar", options);
			System.exit(4);
		}
    }
    
    public void exit() {
    	LogManager.shutdown();
    	if (readerException != null) {
    		System.exit(1);
    	} else if (writerException != null) {
    		System.exit(2);
    	} else {
        	System.exit(0);
    	}
    }
    
    public void initLog4J() throws Exception {
    	if (jobName == null) {
    		throw new Exception("Job name not set!");
    	}
    	logger = Logger.getLogger(jobName);
    	boolean configured = false;
		if (log4jConfigFile != null) {
			try {
				// find the log4j configuration and configure it
				File cf = new File(log4jConfigFile);
				if (cf.exists()) {
					if (log4jConfigFile.endsWith(".xml")) {
						DOMConfigurator.configureAndWatch(cf.getAbsolutePath(), 10000);
						configured = true;
					} else if (log4jConfigFile.endsWith(".properties")) {
						PropertyConfigurator.configureAndWatch(cf.getAbsolutePath(), 10000);
						configured = true;
					} else {
						throw new Exception("Unknown log4j file format:" + log4jConfigFile);
					}
				} else {
					throw new Exception("Log4j config file: " + cf.getAbsolutePath() + " does not exist.");
				}
			} catch (Throwable e) {
				throw new Exception(e);
			}
		}
		if (graylogHost != null) {
			GelfAppender ga = new GelfAppender();
			ga.setGraylogHost(graylogHost);
			String jobStartedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(applicationStartTime);
			String additinalFields = "{'jobName':'" + jobName + "','jobStartedAt':'" + jobStartedAt + "'}";
			ga.setAdditionalFields(additinalFields);
			Logger.getRootLogger().addAppender(ga);
			configured = true;
		}
		if (configured == false) {
			BasicConfigurator.configure();
		}
    }
    
    public void waitUntilEnd() {
    	if (writer != null) {
        	try {
    			writer.join();
    		} catch (InterruptedException e) {
    			// ignore
    		}
    	}
    	if (reader != null) {
        	try {
    			reader.join();
    		} catch (InterruptedException e) {
    			// ignore
    		}
    	}
    }
    
    public void start() {
    	startWriter();
    	startPipeReader();
    }
    
    public void stop() {
    	if (reader != null) {
    		reader.stopReading();
    	}
    	try {
        	dequeue.put(THE_END);
    	} catch (Exception e) {
    		//ignore
    	}
    }
    
    public void startPipeReader() {
    	reader = new ReadPipeThread();
    	reader.setDaemon(true);
    	reader.start();
    }
    
    public void startWriter() {
    	writer = new AggregateAndWriteLogsThread();
    	writer.setDaemon(true);
    	writer.start();
    }
    
    private class ReadPipeThread extends Thread {
    	
    	private boolean stop = false;
    	
    	public void stopReading() {
    		stop = true;
    		try {
    			//System.out.println("put: " + THE_END);
        		dequeue.put(THE_END);
    		} catch (Exception e) {
    			// ignore
    		}
    	}
    	
    	@Override
    	public void run() {
    		try {
    	    	BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"), incomingBufferSize);
    			if (in != null) {
    				String line = null;
    				while (stop == false) {
    					line = in.readLine();
    					if (line != null) {
    						//System.out.println("put: " + line);
        					dequeue.put(line);
    					} else {
    						break;
    					}
    				}
					//System.out.println("put: " + THE_END);
					dequeue.put(THE_END);
    				in.close();
    			}
    		} catch (Exception e) {
    			System.err.println("Read from standard in failed: " + e.getMessage());
    			e.printStackTrace();
    			readerException = e;
    		} finally {
    			try {
        			dequeue.put(THE_END);
    			} catch (Exception e2) {
    				// ignore
    			}
    		}
    	}
    	
    }
    
    public void log(String line) throws Exception {
    	if (line != null && line.trim().isEmpty() == false) {
			//System.out.println("put: " + line);
    		dequeue.put(line);    	
    	}
    }
    
    private class AggregateAndWriteLogsThread extends Thread {
    	
    	@Override
    	public void run() {
    		if (logger == null) {
    			writerException = new Exception("Log4j not configured");
    			writerException.printStackTrace();
    			return;
    		}
    		StringBuilder message = new StringBuilder(1000);
			long lastMessageSendAt = System.currentTimeMillis();
			boolean stop = false;
			while (stop == false) {
				if (Thread.currentThread().isInterrupted()) {
					stop = true;
					break;
				}
				try {
					String line = dequeue.poll(maxTimeBetweenLinesOfAMessage, TimeUnit.MILLISECONDS);
					//System.out.println("poll: " + line);
					boolean sendMessage = false;
					if (line != null) {
						if (line.equals(THE_END)) {
							//System.out.println(THE_END + " received");
							stop = true;
							sendMessage = true;
						} else {
							long currentMessageReceivedAt = System.currentTimeMillis();
							// input within time frame for a message
							message.append(line);
							message.append("\n");
							if ((currentMessageReceivedAt - lastMessageSendAt) > maxTimeToKeepAMessage) {
								sendMessage = true;
							} else {
								sendMessage = false;
							}
							if (message.length() > maxMessageSize) {
								sendMessage = true;
							}
						}
					} else {
						sendMessage = true;
					}
					if (sendMessage) {
						if (message.length() > 0) {
							// input outside time frame of a message
							// send input to the logger
							String s = message.toString();
							if (s.contains("Error") || s.contains("Exception")) {
								logger.error(s);
							} else {
								logger.info(s);
							}
							message.setLength(0);
							lastMessageSendAt = System.currentTimeMillis();
						}
					}
					if (stop) {
						break;
					}
				} catch (InterruptedException ie) {
					stop = true;
					break;
				} catch (Exception e) {
					stop = true;
					System.err.println("Aggregate and send log messages failed: " + e.getMessage());
					e.printStackTrace();
					writerException = e;
					if (reader != null) {
						reader.stopReading();
					}
				}
			}
			LogManager.shutdown();
    	}
    	
    }

	public long getMaxTimeBetweenLinesOfAMessage() {
		return maxTimeBetweenLinesOfAMessage;
	}


	public void setMaxTimeBetweenLinesOfAMessage(long maxTimeBetweenLinesOfAMessage) {
		this.maxTimeBetweenLinesOfAMessage = maxTimeBetweenLinesOfAMessage;
	}

	public String getLog4jConfigFile() {
		return log4jConfigFile;
	}

	public void setLog4jConfigFile(String log4jConfigFile) {
		this.log4jConfigFile = log4jConfigFile;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getGraylogHost() {
		return graylogHost;
	}

	public void setGraylogHost(String graylogHost) {
		this.graylogHost = graylogHost;
	}

	public long getMaxTimeToKeepAMessage() {
		return maxTimeToKeepAMessage;
	}

	public void setMaxTimeToKeepAMessage(long maxTimeToKeepAMessage) {
		this.maxTimeToKeepAMessage = maxTimeToKeepAMessage;
	}

	public int getMaxMessageSize() {
		return maxMessageSize;
	}

	public void setMaxMessageSize(int maxMessageSize) {
		this.maxMessageSize = maxMessageSize;
	}
    
}
