package de.hpi.datastreams.ml;

import de.hpi.datastreams.messages.LabeledData;
import de.hpi.datastreams.messages.LabeledDataWithAge;
import de.hpi.datastreams.messages.MyArrayList;
import de.hpi.datastreams.messages.SerializableHashMap;
import lombok.Getter;
import org.apache.commons.lang.math.IntRange;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.classification.LogisticRegressionModel;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.ml.linalg.Matrices;
import org.apache.spark.ml.linalg.Matrix;
import org.apache.spark.mllib.evaluation.MulticlassMetrics;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class LogisticRegressionTaskSpark {

    public static final int numFeatures = 1024;
    public static final int numClasses = 5;

    private final int numMaxIter = 2;

    @Getter
    private SerializableHashMap weights = new SerializableHashMap();
    @Getter
    boolean isInitialized = false;
    private SparkSession spark;
    private JavaSparkContext sparkContext;

    @Getter
    private Double loss = 1.0;
    @Getter
    private MulticlassMetrics metrics;

    JavaRDD<LabeledPoint> testData;


    public void initialize(boolean randomlyInitializeWeights) {
        // Initialize logisticRegression depending on the size of the weights
        this.initializeSpark();

        if (randomlyInitializeWeights) {
            this.randomlyInitializeWeights();
        }

        this.isInitialized = true;
    }

    /**
     * Initializes local Spark Session
     */
    private void initializeSpark() {
        this.spark = SparkSession.builder()
                .master("local")
                .appName("WorkerTrainingProcessorMLTask")
                .getOrCreate();
        this.sparkContext = JavaSparkContext.fromSparkContext(spark.sparkContext());

        String[] inputCols = IntStream.range(0, 1024).mapToObj(String::valueOf).toArray(String[]::new);
        VectorAssembler assembler = new VectorAssembler()
                .setInputCols(inputCols)
                .setOutputCol("features");

        this.testData = assembler.transform(
                this.spark.sqlContext().read()
                        .format("csv")
                        .option("delimiter", ",")
                        .option("inferSchema", "true")
                        .option("header", "true")
                        .load("./data/reviews_embedded_test.csv")
        ).toJavaRDD()
                .map(row -> {
                    Integer label = (Integer) row.get(row.fieldIndex("Score"));

                    Object features =  row.get(row.fieldIndex("features"));
                    if (features instanceof org.apache.spark.ml.linalg.SparseVector) {
                        org.apache.spark.ml.linalg.SparseVector featuresSparse =
                                (org.apache.spark.ml.linalg.SparseVector) features;
                        return new LabeledPoint(label, org.apache.spark.mllib.linalg.Vectors.dense(featuresSparse.toDense().values()));
                    }
                    else {
                        org.apache.spark.ml.linalg.DenseVector featuresDense =
                                (org.apache.spark.ml.linalg.DenseVector) row.get(row.fieldIndex("features"));
                        return new LabeledPoint(label, org.apache.spark.mllib.linalg.Vectors.dense(featuresDense.values()));
                    }
                })
                .cache();
    }

    /**
     * Initializes ML model's weights
     */
    private void randomlyInitializeWeights() {
        assert this.sparkContext != null;

        for (int i = 0; i < (numClasses + 1) * numFeatures + (numClasses + 1); i++) {
            weights.put(i, 0f);
        }
    }

    /**
     * Helper method to override ML model's existing weights
     *
     * @param newWeights
     */
    public void setWeights(Map<Integer, Float> newWeights) {
        newWeights.forEach((idx, value) -> {
            this.weights.put(idx, value);
        });
    }

    /**
     * Helper method to get weights as array
     *
     * @return
     */
    private double[] getWeightsAsArray() {
        double[] weightsArr = new double[(numClasses + 1) * numFeatures];

        for (int i = 0; i < weightsArr.length; i++) {
            weightsArr[i] = this.weights.get(i);
        }

        return weightsArr;
    }

    private double[] getInterceptAsArray() {
        double[] interceptArr = new double[numClasses + 1];

        for (int i = 0; i < interceptArr.length; i++) {
            interceptArr[i] = this.weights.get((numClasses + 1) * numFeatures + i);
        }

        return interceptArr;
    }

    public SerializableHashMap calculateGradients(MyArrayList<LabeledDataWithAge> dataToBeLearned, int numMaxIter) {

        // Requires initialization
        assert this.isInitialized;

        // Increase age of consumed data
        dataToBeLearned.forEach(LabeledDataWithAge::increaseAge);

        List<Row> localTraining = new ArrayList<>();
        dataToBeLearned.forEach((LabeledDataWithAge ld) -> {
            int size = ld.getInputFeatures().size();
            int[] indices = new int[size];
            double[] values = new double[size];

            ArrayList<Map.Entry<Integer, Float>> sortedFeatures = new ArrayList<>(ld.getInputFeatures().entrySet());
            sortedFeatures.sort(Comparator.comparing(Map.Entry::getKey));

            for (int i = 0; i < sortedFeatures.size(); i++) {
                Map.Entry<Integer, Float> entry = sortedFeatures.get(i);
                indices[i] = entry.getKey();
                values[i] = entry.getValue();
            }

            localTraining.add(RowFactory.create(ld.getLabel(), Vectors.sparse(numFeatures, indices, values).asML()));
        });

        Dataset<Row> training = this.spark.createDataFrame(localTraining, new StructType(new StructField[]{
                new StructField("label", DataTypes.IntegerType, false, Metadata.empty()),
                new StructField("features", org.apache.spark.ml.linalg.SQLDataTypes.VectorType(), false, Metadata.empty())
        }));


        // Create an initial model with the by the ParameterServer calculated weights
        double[] currentWeights = this.getWeightsAsArray();
        double[] currentIntercept = this.getInterceptAsArray();

        Matrix initialCoefficients = Matrices.dense(numClasses + 1, numFeatures, currentWeights);
        LogisticRegressionModel initialModel = new LogisticRegressionModel("model-uid",
                initialCoefficients, org.apache.spark.ml.linalg.Vectors.dense(currentIntercept),
                numClasses, true);

        // Train model starting from the initial model created above
        LogisticRegressionModel model = new LogisticRegression()
                .setMaxIter(numMaxIter)
                .setLabelCol("label")
                .setFeaturesCol("features")
                .setInitialModel(initialModel)
                .fit(training);

        JavaPairRDD<Object, Object> predictionAndLabels = this.testData.mapToPair(p -> {
            double[] featureArr = p.features().toDense().values();
            org.apache.spark.ml.linalg.Vector features = org.apache.spark.ml.linalg.Vectors.dense(featureArr);
            return new Tuple2<>(model.predict(features), p.label());
        });

        // Get evaluation metrics.
        MulticlassMetrics metrics = new MulticlassMetrics(predictionAndLabels.rdd());
        this.metrics = metrics;

        double[] localLossHistory = model.summary().objectiveHistory();
        this.loss = localLossHistory[localLossHistory.length - 1];

        // Extract gradients from trained model
        SerializableHashMap gradients = new SerializableHashMap();

        // Extract weights' gradients
        double[] newWeights = model.coefficientMatrix().toArray();
        for (int j = 0; j < newWeights.length; j++) {
            float oldWeight = (float) currentWeights[j];
            float newWeight = (float) newWeights[j];

            float gradient = newWeight - oldWeight;
            gradients.put(j, gradient);
        }

        // Extract intercept's gradients
        double[] newIntercepts = model.interceptVector().toArray();
        for (int j = 0; j < currentIntercept.length; j++) {
            float oldIntercept = (float) currentIntercept[j];
            float newIntercept = (float) newIntercepts[j];

            float gradient = newIntercept - oldIntercept;
            gradients.put(newWeights.length + j, gradient);
        }

        return gradients;
    }

    public void calculateTestMetrics() {
        // Create an initial model with the by the ParameterServer calculated weights
        double[] currentWeights = this.getWeightsAsArray();
        double[] currentIntercept = this.getInterceptAsArray();

        Matrix initialCoefficients = Matrices.dense(numClasses + 1, numFeatures, currentWeights);
        LogisticRegressionModel model = new LogisticRegressionModel("model-uid",
                initialCoefficients, org.apache.spark.ml.linalg.Vectors.dense(currentIntercept),
                numClasses, true);

        JavaPairRDD<Object, Object> predictionAndLabels = this.testData.mapToPair(p -> {
            double[] featureArr = p.features().toDense().values();
            org.apache.spark.ml.linalg.Vector features = org.apache.spark.ml.linalg.Vectors.dense(featureArr);
            return new Tuple2<>(model.predict(features), p.label());
        });

        // Get evaluation metrics.
        MulticlassMetrics metrics = new MulticlassMetrics(predictionAndLabels.rdd());
        this.metrics = metrics;
    }

    /**
     * Run ML model and return calculated gradients
     *
     * @param dataToBeLearned
     * @return
     */
    public SerializableHashMap calculateGradients(MyArrayList<LabeledDataWithAge> dataToBeLearned) {
        return this.calculateGradients(dataToBeLearned, numMaxIter);
    }

    public float predict(LabeledData dataToBePredicted) {
        double[] currentWeights = this.getWeightsAsArray();
        int size = dataToBePredicted.getInputData().size();
        int[] indices = new int[size];
        double[] values = new double[size];
        dataToBePredicted.getInputData().forEach((idx, value) -> {
            indices[idx] = idx;
            values[idx] = value;
        });

        return (float) new LogisticRegressionModel("prediction-uid", org.apache.spark.ml.linalg.Vectors.dense(currentWeights), 0f)
                .setThreshold(0.5) // if this is not set explicitly it fails
                .predict(Vectors.sparse(size, indices, values).asML());
    }
}
