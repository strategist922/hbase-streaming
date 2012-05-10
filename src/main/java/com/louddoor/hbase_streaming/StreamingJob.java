package com.louddoor.hbase_streaming;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Map.Entry;
import java.util.NavigableMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class StreamingJob {
	
	static final String NAME = "";
	
	public static class StreamingMapper extends TableMapper<Text, Text>
	{
		Process proc = null;
		OutputStream out;
		BufferedWriter writeOut;
		ProcessInputMapperReader procin;
		
		String line = "";
		NavigableMap<byte[], NavigableMap<byte[], byte[]>> map;
		
		public void map(ImmutableBytesWritable rowKey, Result values, Context context) 
		throws IOException,  InterruptedException
		{
			map(rowKey, values, context, 0);
		}

		public void map(ImmutableBytesWritable rowKey, Result values, Context context, int retries) 
		throws IOException,  InterruptedException
		{
			map = values.getNoVersionMap();			
			
			try {
				
				JSONObject val = new JSONObject();
				
				for(Entry<byte[], NavigableMap<byte[], byte[]>> ent : map.entrySet())
				{
					JSONObject innerVal = new JSONObject();

					for(Entry<byte[], byte[]> inner : ent.getValue().entrySet())
					{
						Object value;
						
						try {
							value = Bytes.toInt(inner.getValue());
						} catch(Exception e) {
							value = Bytes.toString(inner.getValue());
						}
						
						innerVal.put(Bytes.toString(inner.getKey()), value);
						
					}

					val.put(Bytes.toString(ent.getKey()), innerVal);
				}
				
				line = Bytes.toString(rowKey.get()) + "\t" + val.toString() + "\n";
				
				writeOut.write(line);
				
			} catch(Exception e) {
				
				if(e.getMessage().contains("pipe"))
				{
					if(retries > 5)
					{
						throw new InterruptedException("Mapper failed to launch process 5 times - Check err logs");
					}
					
					setupProc(context);
					map(rowKey, values, context, retries + 1);
				}
				
				e.printStackTrace();
			}

			
		}

		public void setup(Context context)
		throws IOException
		{
			try {
				StreamingUtils.downloadFiles(context);
				setupProc(context);
			} catch (JSONException e) {
				e.printStackTrace();
			}
			
		}
		
		public void setupProc(Context context) throws IOException{
			proc = StreamingUtils.buildProcess(context.getConfiguration().get("mapper.command"));
			
			out = proc.getOutputStream();
			writeOut = new BufferedWriter(new OutputStreamWriter(out));
			
			if(procin != null)
			{
				procin.stopThread();
				procin.interrupt();
			}
			
			procin = new ProcessInputMapperReader(proc, context);
			procin.start();
		}
	}
	
	public static class StreamingReducer extends Reducer<Text, Text, Text, Text>
	{
		Process proc = null;
		OutputStream out;
		BufferedWriter writeOut;
		
		ProcessInputReducerReader procin;
		
		String line = "";
		NavigableMap<byte[], NavigableMap<byte[], byte[]>> map;
		
		public void reduce(Text id, Iterable<Text> values, Context context)
			throws IOException, InterruptedException 
		{
			JSONArray vals = new JSONArray();
			
			for(Text val : values)
			{
				vals.put(val.toString());
			}
			
			reduce(id, vals, context, 0);
		}
		
		public void reduce(Text id, JSONArray values, Context context, int retries)
			throws IOException, InterruptedException 
		{
			try {
				
				line = id + "\t" + values.toString() + "\n";
				
				writeOut.write(line);
				
			} catch(Exception e) {				
				if(e.getMessage().contains("pipe"))
				{
					if(retries > 5)
					{
						throw new InterruptedException("Mapper failed to launch process 5 times - Check err logs");
					}
					
					setupProc(context);
					reduce(id, values, context, retries + 1);
				}
				
				e.printStackTrace();
			}

			
		}

		public void setup(Context context)
		throws IOException
		{
			try {
				StreamingUtils.downloadFiles(context);
				setupProc(context);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		public void setupProc(Context context) throws IOException{
			proc = StreamingUtils.buildProcess(context.getConfiguration().get("mapper.command"));
			
			out = proc.getOutputStream();
			writeOut = new BufferedWriter(new OutputStreamWriter(out));
			
			if(procin != null)
			{
				procin.stopThread();
				procin.interrupt();
			}
			
			procin = new ProcessInputReducerReader(proc, context);
			procin.start();
		}
	
	}
	
	@SuppressWarnings("static-access")
	public static Options setOptions() {
		Options options = new Options();
		
		options.addOption(new Option("help", "print this message"));
		
		Option configOption = OptionBuilder.withArgName("file").hasArg()
							.withDescription("set config file")
							.create("configFile");
		
		Option numReducers = OptionBuilder.withArgName("num").hasArg()
							.withDescription("Number of reducers")
							.create("numReducers");
		
		Option files = OptionBuilder.withArgName("file").hasArgs()
							.withDescription("All files required by job")
							.create("file");
		
		Option reducerCmd = OptionBuilder.withArgName("cmd").hasArgs()
							.withDescription("Reducer Command")
							.create("reducer");
		
		Option mapperCmd = OptionBuilder.withArgName("cmd").hasArgs()
							.withDescription("Mapper Command")
							.create("mapper");
		
		Option name = OptionBuilder.withArgName("name").hasArgs()
							.withDescription("Name of the job for reference")
							.create("name");
		
		options.addOption(configOption);
		options.addOption(files);
		options.addOption(numReducers);
		options.addOption(reducerCmd);
		options.addOption(mapperCmd);
		options.addOption(name);
		
		return options;
	}
	
	public static JSONObject loadConfig(String file) 
		throws JSONException, IOException 
	{
		return new JSONObject(readFullFile(file));	
	}
	
	public static String readFullFile(String file) 
		throws IOException 
	{
		FileReader in = new FileReader(file);
		StringBuilder contents = new StringBuilder();

		char[] buffer = new char[4096];

		int read = 0;

		do {
			contents.append(buffer, 0, read);
			read = in.read(buffer);
		} while (read >= 0);

		return contents.toString();
	}

	public static Job configureJob(Configuration conf, String [] args)
		throws Exception
	{
		Options options = setOptions();
		CommandLineParser parser = new GnuParser();
		CommandLine line = parser.parse(options, args);		
		Job job = new Job();
		Scan scan = new Scan();
		String table = "";
		String outFile = "";
		boolean overwrite = true;
		
		String name = line.getOptionValue("name");
		
		
		job.setJarByClass(StreamingJob.class);
		
		if(line.hasOption("help"))
		{
			HelpFormatter formatter = new HelpFormatter();
			
			formatter.printHelp("ant", options);
			
			System.exit(0);
		}
		
		String mapperCommand = line.getOptionValue("mapper");
		String reducerCommand = line.getOptionValue("reducer");
		
		job.getConfiguration().set("mapper.command", mapperCommand);
		job.getConfiguration().set("reducer.command", reducerCommand);
		
		if(line.hasOption("file")) {
			
			String[] files = line.getOptionValues("file");
			JSONObject filesObj = new JSONObject();
			
			for(String file : files)
			{
				String contents = readFullFile(file);
				
				filesObj.put(file, contents);
			}
			
			job.getConfiguration().set("files", filesObj.toString());
		}
		
		
		String fileName = line.getOptionValue("config", "config.json");
		
		JSONObject config = loadConfig(fileName);
		
		if(config.has("input")){
			JSONObject input = config.getJSONObject("input");
			
			if(input.has("scan_caching"))
			{
				scan.setCaching(input.getInt("scan_caching"));
			}
			
			if(input.has("hbase_table"))
			{
				table = input.getString("hbase_table");
			}
			
			if(input.has("families"))
			{
				JSONArray families = input.getJSONArray("families");
				
				for(int i = 0; i < families.length(); i++)
				{
					scan.addFamily(Bytes.toBytes(families.getString(i)));
				}
			}
		}
		
		if(config.has("output"))
		{
			JSONObject output = config.getJSONObject("output");
			
			if(output.has("type"))
			{
				
			}
			
			if(output.has("path"))
			{
				outFile = output.getString("path");				
			}
			
			if(output.has("overwrite"))
			{
				overwrite = output.getBoolean("overwrite");
			}
		}
		
		if(config.has("name") && name == null)
		{
			name = config.getString("name");
		}
		
		if(name == null)
			name = NAME;
		
		job.setJobName(name);
		job.setInputFormatClass(TableInputFormat.class);
		
		TableMapReduceUtil.initTableMapperJob(table, scan, StreamingMapper.class, Text.class, Text.class, job);
		
		job.setReducerClass(StreamingReducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		job.setOutputFormatClass(TextOutputFormat.class);
		
		Configuration conffs = new Configuration();
		
		FileSystem fsA = FileSystem.get(conffs);
		
		Path outPath = new Path(outFile);

		if(fsA.exists(outPath)){
			if(overwrite)
			{
				fsA.delete(outPath, true);
			}
			else
			{
				throw new Exception("File already exists: " + outPath.toString());
			}
		}
		
		TextOutputFormat.setOutputPath(job, outPath);
		
		return job;
	}
	
	public static void main(String args[]) throws Exception 
	{
		Configuration conf = HBaseConfiguration.create();
		String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
		
		Job job = configureJob(conf, otherArgs);
				
		job.waitForCompletion(true);
		
	}
}