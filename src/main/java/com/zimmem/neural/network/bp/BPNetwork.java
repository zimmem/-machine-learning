package com.zimmem.neural.network.bp;

import com.zimmem.math.Functions;
import com.zimmem.mnist.*;
import com.zimmem.neural.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Created by zimmem on 2016/7/26.
 */
public class BPNetwork implements Network, Serializable {

    private Logger log = LoggerFactory.getLogger(BPNetwork.class);

    private static ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    Layer inputLayer;

    Layer outputLayer;

    @Override
    public void train(List<MnistImage> images, List<MnistLabel> labels, int batchSize, int repeat) {
        long start = System.currentTimeMillis();
        log.info("begin to train at {}", start);

        for(int i = 0 ; i < images.size(); i++ ){
            images.get(i).setLabel(labels.get(i).getValue());
        }

        while (repeat-- > 0) {
            //重复 repeat 次

            Collections.shuffle(images);

            int correct = 0;
            AtomicInteger batchCorrect = new AtomicInteger(0);
            double verifyRate = 0d;
            for (int batch = 0; batch < images.size(); batch += batchSize) {

                if (batch % 1000 == 0) {
                    // 每训练1000个数据， 拿最后1000个数据做下验证
                    verifyRate = verify(images.subList(images.size() - 1000, images.size()));
                }

                batchCorrect.set(0);
                List<TrainContext> contexts = new ArrayList<>(batchSize);
                CountDownLatch latch = new CountDownLatch(batchSize);
                for (int index = batch; index < batch + batchSize; index++) {
                    MnistImage image = images.get(index);
                    executor.execute(() -> {
                        TrainContext context = new TrainContext();
                        synchronized (contexts) {
                            contexts.add(context);
                        }
                        double[] output = forward(context, image);

                        if (!Double.isNaN(output[image.getLabel()]) && Objects.equals(Arrays.stream(output).max().getAsDouble(), output[image.getLabel()])) {
                            //System.out.println(Arrays.toString(output));
                            batchCorrect.getAndIncrement();
                        }

                        // 输出与期望的偏差
                        double[] expect = new double[output.length];
                        expect[image.getLabel()] = 1;
                        double[] error = new double[output.length];
                        for (int i = 0; i < output.length; i++) {
                            error[i] = output[i] - expect[i];
                        }

                        // 计算 output 层偏差
                        double[] deltas = new double[outputLayer.size];
                        IntStream.range(0, outputLayer.size).forEach(i -> deltas[i] = error[i] * Functions.SigmoidDerivative.apply(context.weightedInputs.get(outputLayer)[i]));
                        outputLayer.backPropagationDelta(context, deltas);
                        latch.countDown();
                    });
                }
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                correct += batchCorrect.get();
                log.debug("batch {} : {}/{} - total {}/{} = {} ", batch / batchSize + 1, batchCorrect, batchSize, correct, batch + batchSize, (double) correct / (batch + batchSize));

                outputLayer.backPropagationUpdate(contexts, (1 - verifyRate) * .5 );
                //resetTrainData();


            }
            log.info("repeat {}:  {} / {} ", repeat, correct, images.size());

        }

        log.info("train finish, cast {} ms", System.currentTimeMillis() - start);
    }

    private double verify(List<MnistImage> mnistImages) {
        AtomicInteger collect = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(mnistImages.size());
        IntStream.range(0, mnistImages.size()).forEach(i -> {
            executor.execute(() -> {
                double[] output = forward(new TrainContext(), mnistImages.get(i));
                double max = Arrays.stream(output).max().getAsDouble();
                if (Objects.equals(max, output[mnistImages.get(i).getLabel()]) && !Double.isNaN(max)) {
                    collect.incrementAndGet();
                }
                latch.countDown();
            });
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        double rate = collect.doubleValue() / mnistImages.size();
        log.info("verified {}/{} = {}", collect, mnistImages.size(), rate);
        return rate;
    }


    /**
     * 分类
     *
     * @param context
     * @param image
     * @return
     */
    public double[] forward(TrainContext context, MnistImage image) {

        double[] input = new double[image.getValues().length];
        for (int i = 0; i < image.getValues().length; i++) {
            input[i] = (double) (0xff & image.getValues()[i]);
        }
        context.activations.put(inputLayer, input);
        return inputLayer.forward(context);

    }


}
