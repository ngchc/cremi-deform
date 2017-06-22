package org.janelia.saalfeldlab.deform;

import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import bdv.img.h5.H5LabelMultisetSetupImageLoader;
import bdv.img.h5.H5Utils;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Pair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.RandomAccessiblePair;
import net.imglib2.view.Views;

public class FlattenNeuronIds
{
	static private class Parameters
	{
		@Parameter( names = { "--infile", "-i" }, description = "Input file path" )
		public String inFile;

		@Parameter( names = { "--outfile", "-o" }, description = "Output file path" )
		public String outFile;

		@Parameter( names = { "--label", "-l" }, description = "label dataset" )
		public String label;

		@Parameter( names = { "--slice", "-s" }, description = "slices to be cleared from any labels" )
		public List< Long > badSlices = Arrays.asList( new Long[ 0 ] );

		@Parameter( names = { "--canvas", "-c" }, description = "canvas dataset" )
		public String canvas;

		@Parameter( names = { "--export", "-e" }, description = "export dataset" )
		public String export = "/volumes/labels/merged_neuron_ids";

		public void init()
		{
			if ( outFile == null )
				outFile = inFile;
		}
	}

	final static private int[] cellDimensions = new int[]{ 64, 64, 8 };

	public static void main( final String[] args ) throws Exception
	{
		final Parameters params = new Parameters();
		new JCommander( params, args );
		params.init();

		System.out.println( "Opening " + params.inFile );
		final IHDF5Writer writer = HDF5Factory.open( params.outFile );
		final IHDF5Reader reader;
		if ( params.inFile == params.outFile )
			reader = writer;
		else
			reader = HDF5Factory.openForReading( params.inFile );

		/* dimensions */
		final long[] maxRawDimensions = new long[]{ 0, 0, 0 };

		/* labels */
		final H5LabelMultisetSetupImageLoader labelLoader;
		if ( reader.exists( params.label ) )
		{
			 labelLoader = new H5LabelMultisetSetupImageLoader(
							reader,
							null,
							params.label,
							1,
							cellDimensions );
			labelLoader.getVolatileImage( 0, 0 ).dimensions( maxRawDimensions );
		}
		else
		{
			System.err.println( "no label dataset '" + params.label + "' found" );
			return;
		}

		/* resolution */
		final double[] resolution;
		if ( reader.object().hasAttribute( params.label, "resolution" ) )
			resolution = reader.float64().getArrayAttr( params.label, "resolution" );
		else
			resolution = new double[] { 1, 1, 1 };

		/* offset */
		final double[] offset;
		if ( reader.object().hasAttribute( params.label, "offset" ) )
			offset = reader.float64().getArrayAttr( params.label, "resolution" );
		else
			offset = new double[] { 0, 0, 0 };

		/* canvas (to which the brush paints) */
		/* TODO this has to change into a virtual container with temporary storage */
		final CellImg< LongType, ?, ? > canvas;
		if ( params.canvas != null && reader.exists( params.canvas ) )
			canvas = H5Utils.loadUnsignedLong( reader, params.canvas, cellDimensions );
		else
		{
			canvas = new CellImgFactory< LongType >( cellDimensions ).create( maxRawDimensions, new LongType() );
			for ( final LongType t : canvas )
				t.set( Label.TRANSPARENT );
		}

		/* pair labels */
		final RandomAccessiblePair< LabelMultisetType, LongType > labelCanvasPair =
				new RandomAccessiblePair<>(
						labelLoader.getImage( 0 ),
						canvas );
		final RandomAccessibleInterval< Pair< LabelMultisetType, LongType > > pairInterval =
				Views.offsetInterval( labelCanvasPair, canvas );

		final Converter< Pair< LabelMultisetType, LongType >, LongType > converter =
				new Converter< Pair< LabelMultisetType, LongType >, LongType >()
				{
					@Override
					public void convert(
							final Pair< LabelMultisetType, LongType > input,
							final LongType output )
					{
						final long inputB = input.getB().get();
						if ( inputB == Label.TRANSPARENT )
						{
							output.set( input.getA().entrySet().iterator().next().getElement().id() );
						}
						else
						{
							output.set( inputB );
						}
					}
				};

		final RandomAccessibleInterval< LongType > source =
				Converters.convert(
						pairInterval,
						converter,
						new LongType() );

		System.out.println( "Reading merged labels into canvas..." );

		/* copy merge into canvas */
		final Cursor< LongType > sourceCursor = Views.flatIterable( source ).cursor();
		final Cursor< LongType > targetCursor = Views.flatIterable( canvas ).cursor();

		while ( sourceCursor.hasNext() )
		{
			final LongType sourceType = sourceCursor.next();
			final LongType targetType = targetCursor.next();
			final long sourceValue = sourceType.get();
			targetType.set( sourceValue );
		}

		/* clear bad slices */
		for ( final long z : params.badSlices )
		{
			System.out.println( "Clearing bad slice " + z + "..." );
			final IntervalView< LongType > slice = Views.hyperSlice( canvas, 2, z );
			for ( final LongType t : slice )
				t.set( Label.INVALID );
		}

		System.out.println( "Saving " + params.export + "..." );

		H5Utils.saveUnsignedLong( canvas, writer, params.export, cellDimensions );
		H5Utils.saveAttribute( resolution, writer, params.export, "resolution" );

		writer.close();
		reader.close();

		System.out.println( "Done" );
	}
}
