package chibi.gemmaanalysis;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.lang.time.StopWatch;

import ubic.basecode.dataStructure.matrix.DenseDoubleMatrix3DNamed;
import ubic.basecode.dataStructure.matrix.DoubleMatrixNamed;
import ubic.basecode.io.writer.MatrixWriter;
import ubic.gemma.analysis.preprocess.filter.FilterConfig;
import ubic.gemma.model.expression.experiment.ExpressionExperiment;
import ubic.gemma.model.expression.experiment.ExpressionExperimentService;
import ubic.gemma.model.genome.Gene;
import ubic.gemma.model.genome.Taxon;
import ubic.gemma.model.genome.TaxonService;
import ubic.gemma.model.genome.gene.GeneService;
import ubic.gemma.ontology.GeneOntologyService;
import chibi.gemmaanalysis.CoexpressionAnalysisService.CoexpressionMatrices;

/**
 * Calculate the effect size
 * 
 * @author xwan
 * @author raymond
 */
public class EffectSizeCalculationCli extends AbstractGeneCoexpressionManipulatingCLI {
    private String goTerm;

    private String outFilePrefix;

    private Taxon taxon;

    private CoexpressionAnalysisService coexpressionAnalysisService;

    private GeneOntologyService goService;;

    public static final int DEFAULT_STRINGENCY = 3;

    public EffectSizeCalculationCli() {
        super();
    }

    @SuppressWarnings("static-access")
    @Override
    protected void buildOptions() {
        super.buildOptions();
        Option goOption = OptionBuilder.hasArg().withArgName( "GOTerm" ).withDescription( "Target GO term" )
                .withLongOpt( "GOTerm" ).create( 'g' );
        addOption( goOption );
        Option outputFileOption = OptionBuilder.hasArg().isRequired().withArgName( "outFilePrefix" ).withDescription(
                "File prefix for saving the correlation data" ).withLongOpt( "outFilePrefix" ).create( 'o' );
        addOption( outputFileOption );
        Option stringencyOption = OptionBuilder.hasArg().withArgName( "stringency" ).withDescription(
                "Vote count stringency for link selection" ).withLongOpt( "stringency" ).create( 'r' );
        addOption( stringencyOption );
    }

    @Override
    protected void processOptions() {
        super.processOptions();
        if ( hasOption( 'g' ) ) {
            this.goTerm = getOptionValue( 'g' );
        }
        if ( hasOption( 'g' ) ) {
            this.goTerm = getOptionValue( 'g' );
        }
        String taxonName = getOptionValue( 't' );
        taxon = Taxon.Factory.newInstance();
        taxon.setCommonName( taxonName );
        TaxonService taxonService = ( TaxonService ) this.getBean( "taxonService" );
        taxon = taxonService.find( taxon );
        if ( taxon == null ) {
            log.info( "No Taxon found!" );
        }
        if ( hasOption( 'o' ) ) {
            this.outFilePrefix = getOptionValue( 'o' );
        }

        initBeans();
    }

    protected void initBeans() {
        coexpressionAnalysisService = ( CoexpressionAnalysisService ) this.getBean( "coexpressionAnalysisService" );
        eeService = ( ExpressionExperimentService ) this.getBean( "expressionExperimentService" );
        geneService = ( GeneService ) this.getBean( "geneService" );
    }

    @Override
    protected Exception doWork( String[] args ) {
        Exception exc = processCommandLine( "EffectSizeCalculation ", args );
        if ( exc != null ) {
            return exc;
        }
        StopWatch watch = new StopWatch();
        watch.start();

        Collection<Gene> queryGenes, targetGenes;
        try {

            queryGenes = getQueryGenes();
            targetGenes = getTargetGenes();
        } catch ( IOException e ) {
            return e;
        }
        if ( goTerm != null ) {
            while ( !goService.isReady() ) {
                try {
                    Thread.sleep( 1000 );
                } catch ( InterruptedException e ) {
                }
            }
            targetGenes.addAll( goService.getGenes( goTerm, taxon ) );
        }

        if ( targetGenes.size() == 0 || queryGenes.size() == 0 ) {
            return new Exception( "No genes in query/target" );
        }

        FilterConfig filterConfig = new FilterConfig();
        CoexpressionMatrices matrices = coexpressionAnalysisService.calculateCoexpressionMatrices(
                expressionExperiments, queryGenes, targetGenes, filterConfig, null );
        DenseDoubleMatrix3DNamed<Gene, Gene, ExpressionExperiment> correlationMatrix = matrices.getCorrelationMatrix();
        DenseDoubleMatrix3DNamed<Gene, Gene, ExpressionExperiment> sampleSizeMatrix = matrices.getSampleSizeMatrix();
        DoubleMatrixNamed<Gene, Gene> effectSizeMatrix = coexpressionAnalysisService.calculateEffectSizeMatrix(
                correlationMatrix, sampleSizeMatrix );

        // create 2D correlation heat map
        // ColorMatrix dataColorMatrix = new ColorMatrix( correlationMatrix2D );
        // dataColorMatrix.setColorMap( ColorMap.GREENRED_COLORMAP );
        // JMatrixDisplay dataMatrixDisplay = new JMatrixDisplay( dataColorMatrix );
        // String figureFileName = outFilePrefix + ".corr.png";

        // create row/col name maps
        Map<ExpressionExperiment, String> eeNameMap = matrices.getEeNameMap();

        DecimalFormat formatter = ( DecimalFormat ) NumberFormat.getNumberInstance();
        formatter.applyPattern( "0.0000" );
        DecimalFormatSymbols symbols = formatter.getDecimalFormatSymbols();
        symbols.setNaN( "" );
        formatter.setDecimalFormatSymbols( symbols );
        String topLeft = "GenePair";
        try {
            MatrixWriter out = new MatrixWriter( outFilePrefix + ".corr.txt", formatter );
            out.setColNameMap( eeNameMap );
            out.setTopLeft( topLeft );
            out.writeMatrix( correlationMatrix, true );

            out = new MatrixWriter( outFilePrefix + ".effect_size.txt", formatter );
            out.setColNameMap( eeNameMap );
            out.setTopLeft( topLeft );
            out.writeMatrix( effectSizeMatrix, true );

            // dataMatrixDisplay.saveImage( figureFileName, true );
            // log.info( "Saved correlation image to " + figureFileName );
        } catch ( IOException e ) {
            return e;
        }

        return null;
    }

    public static void main( String[] args ) {
        EffectSizeCalculationCli analysis = new EffectSizeCalculationCli();
        StopWatch watch = new StopWatch();
        watch.start();
        log.info( "Starting Effect Size Analysis" );
        Exception exc = analysis.doWork( args );
        if ( exc != null ) {
            log.error( exc.getMessage() );
        }
        log.info( "Finished analysis in " + watch.getTime() / 1000 + " seconds" );
    }
}