package chibi.gemmaanalysis;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.lang3.time.StopWatch;

import cern.colt.list.DoubleArrayList;
import ubic.basecode.math.distribution.HistogramSampler;
import ubic.gemma.core.apps.ExpressionExperimentManipulatingCLI;
import ubic.gemma.model.expression.experiment.BioAssaySet;
import ubic.gemma.model.expression.experiment.ExpressionExperiment;

public class CorrelationHistogramSamplerCLI extends ExpressionExperimentManipulatingCLI {
    public static final int DEFAULT_NUM_SAMPLES = 1000;

    public static final int DEFAULT_K_MAX = 5;

    /**
     * @param args
     */
    public static void main( String[] args ) {
        CorrelationHistogramSamplerCLI analysis = new CorrelationHistogramSamplerCLI();
        executeCommand( analysis, args );
    }

    private CoexpressionAnalysisService coexprAnalysisService;
    private int numSamples;

    private String outFileName;

    private int kMax;

    /*
     * (non-Javadoc)
     *
     * @see ubic.gemma.util.AbstractCLI#getCommandName()
     */
    @Override
    public String getCommandName() {
        return "corrhist";
    }

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
    protected Exception doWork( String[] args ) {
        Exception exc = processCommandLine( args );
        if ( exc != null ) return exc;

        Collection<HistogramSampler> samplers = coexprAnalysisService.getHistogramSamplers( this.getExpressionExperiments() );

        log.info( "Sampling " + samplers.size() + " expression experiments" );
        log.info( "Taking the n-" + kMax + " largest value " + numSamples + " times" );
        StopWatch watch = new StopWatch();
        watch.start();
        double[] samples = new double[numSamples];
        for ( int i = 0; i < numSamples; i++ ) {
            DoubleArrayList eeSamples = new DoubleArrayList( getExpressionExperiments().size() );
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
        for ( BioAssaySet bas : getExpressionExperiments() ) {
            ExpressionExperiment ee = ( ExpressionExperiment ) bas;
            header += ee.getShortName() + " ";
        }

        try (PrintWriter out = new PrintWriter( new FileWriter( outFileName ) );) {
            out.println( header );
            for ( double d : samples )
                out.println( d );

        } catch ( IOException e ) {
            return e;
        }
        log.info( "Wrote samples to " + outFileName );

        return null;
    }

    @Override
    protected void processOptions() {
        super.processOptions();

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

        coexprAnalysisService = getBean( CoexpressionAnalysisService.class );
    }

}
