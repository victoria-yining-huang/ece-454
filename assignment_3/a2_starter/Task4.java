import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayPrimitiveWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

public class Task4 {
  public static class ParsingMapper extends Mapper<Object, Text, Text, ArrayPrimitiveWritable> {
    private Text movieTitle = new Text();
    private final static IntWritable rating = new IntWritable();

    public void map(Object key, Text value, Context context) throws IOException, InterruptedException {

      String[] tokens = value.toString().split(",", -1);
      int[] ratings = new int[tokens.length - 1];

      movieTitle.set(tokens[0]);

      int val = 0;
      for (int i = 1; i < tokens.length; i++) {
        String token = tokens[i];

        if (token.equals("")) {
          val = 0;
        } else {
          val = Integer.valueOf(token);
        }

        ratings[i - 1] = val;
      }

      context.write(movieTitle, new ArrayPrimitiveWritable(ratings));
    }

  }


  public static class CartesionReducer extends Reducer<Text, ArrayPrimitiveWritable, Text, Text> {
    private static HashMap<String, int[]> inMemory = new HashMap<>();
    private static Text moviePair = new Text();
    private static Text ratingCosine = new Text();


    public void reduce(Text key, Iterable<ArrayPrimitiveWritable> values, Context context) throws IOException, InterruptedException {
      String movieTitle = key.toString();
      System.out.println(movieTitle);

      int[] ratings = (int[]) values.iterator().next().get();

      StringBuilder sb;
      for (Map.Entry<String, int[]> entry : inMemory.entrySet()) {
        sb = new StringBuilder();
        System.out.println(entry.getKey());

        if (movieTitle.compareTo(entry.getKey()) < 0) {
          sb.append(movieTitle).append(",").append(entry.getKey());
        } else {
          sb.append(entry.getKey()).append(",").append(movieTitle);
        }

        double res = calculate(ratings, entry.getValue());

        moviePair.set(sb.toString());
        ratingCosine.set(String.format("%1.2f", res));

        context.write(moviePair, ratingCosine);
      }
      
      inMemory.put(movieTitle, ratings);
    }

    public double calculate(int[] a, int[] b) {

      double mul = 0;
      double aSum = 0;
      double bSum = 0;

      for (int i = 0; i < a.length; i++) {
        mul += a[i] * b[i];
        aSum += a[i] * a[i];
        bSum += b[i] * b[i];
      }

      return (mul / (Math.sqrt(aSum) * Math.sqrt(bSum)));
    }
  }


  public static void main(String[] args) throws Exception {
    Configuration conf = new Configuration();
    conf.set("mapred.textoutputformat.separator", ",");

    String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
    if (otherArgs.length != 2) {
      System.err.println("Usage: rating avg <in> <out>");
      System.exit(2);
    }

    Job job = new Job(conf, "cartesion product calc");
    job.setJarByClass(Task4.class);
    job.setMapperClass(Task4.ParsingMapper.class);
    job.setReducerClass(Task4.CartesionReducer.class);
    job.setNumReduceTasks(1);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(ArrayPrimitiveWritable.class);
    TextInputFormat.addInputPath(job, new Path(otherArgs[0]));
    TextOutputFormat.setOutputPath(job, new Path(otherArgs[1]));
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }


}
