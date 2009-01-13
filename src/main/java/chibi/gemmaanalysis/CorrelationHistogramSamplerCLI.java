package chibi.gemmaanalysis;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.lang.time.StopWatch;

import ubic.basecode.math.distribution.HistogramSampler;
import ubic.gemma.apps.ExpressionExperimentManipulatingCLI;
import ubic.gemma.model.expression.experiment.BioAssaySet;
import ubic.gemma.model.expression.experiment.ExpressionExperiment;
import ubic.gemma.model.genome.Taxon;
import cern.colt.list.DoubleArrayList;

public class CorrelationHistogramSamplerCLI extends ExpressionExperimentManipulatingCLI {
    private CoexpressionAnalysisService coexprAnalysisService;
    private Taxon taxon;
    private int numSamples;
    private String outFileName;
    private int kMax;
    public static final int DEFAULT_NUM_SAMPLES = 1000;

    public static final int DEFAULT_K_MAX = 5;

    @Override
    protected void buildOptions() {
        super.buildOptions();
        Option taxonOption = OptionBuilder.create( 't' );
        addOption( taxonOption );
        Option numSamplesOption = OptionBuilder.create( 'n' );
        addOption( numSamplesOption );

        Option outFileOption = OptionBuilder.create( 'o' );
        addOption( outFileOption );

        Option kMaxOption = OptionBuilder.create( 'k' );
        addOption( kMaxOption );

    }

    @Override
    protected void processOptions() {
        super.processOptions();
        String taxonName = getOptionValue( 't' );
        taxon = Taxon.Factory.newInstance();
        taxon.setCommonName( taxonName );
        taxon = taxonService.find( taxon );

        if ( hasOption( 'n' ) ) {
            numSamples = getIntegerOptionValue( 'n' );
        } else {
            numSamples = DEFAULT_NUM_SAMPLES;
        }
        if ( hasOption( 'k' ) ) {
            kMax = getIntegerOptionValue( 'k' );
        } else {
            kMax = DEFAULT_K_MAX;
        }
        outFileName = getOptionValue( 'o' );

        coexprAnalysisService = ( CoexpressionAnalysisService ) getBean( "coexpressionAnalysisService" );
    }

    @Override
    protected Exception doWork( String[] args ) {
        Exception exc = processCommandLine( "CorrelationHistogramSampling", args );
        if ( exc != null ) return exc;

        Collection<HistogramSampler> samplers = coexprAnalysisService.getHistogramSamplers( this.expressionExperiments );

        log.info( "Sampling " + samplers.size() + " expression experiments" );
        log.info( "Taking the n-" + kMax + " largest value " + numSamples + " times" );
        StopWatch watch = new StopWatch();
        watch.start();
        double[] samples = new double[numSamples];
        for ( int i = 0; i < numSamples; i++ ) {
            DoubleArrayList eeSamples = new DoubleArrayList( expressionExperiments.size() );
            for ( HistogramSampler sampler : samplers ) {
                eeSamples.add( sampler.nextSample() );
            }
            log.debug( eeSamples.toString() );
            eeSamples.sort();
            samples[i] = eeSamples.get( eeSamples.size() - 1 - kMax );
        }
        watch.stop();
        log.info( "Finished sampling in " + watch );

        String header = "# ";
        for ( BioAssaySet bas : expressionExperiments ) {
            ExpressionExperiment ee = ( ExpressionExperiment ) bas;
            header += ee.getShortName() + " ";
        }

        try {
            PrintWriter out = new PrintWriter( new FileWriter( outFileName ) );
            out.println( header );
            for ( double d : samples )
                out.println( d );
            out.close();
        } catch ( IOException e ) {
            return e;
        }
        log.info( "Wrote samples to " + outFileName );

        return null;
    }

    /**
     * @param args
     */
    public static void main( String[] args ) {
        CorrelationHistogramSamplerCLI analysis = new CorrelationHistogramSamplerCLI();
        Exception exc = analysis.doWork( args );
        if ( exc != null ) log.error( exc.getMessage() );
    }

}
