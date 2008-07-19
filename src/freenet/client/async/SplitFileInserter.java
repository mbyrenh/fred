/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketTools;

public class SplitFileInserter implements ClientPutState {

	private static volatile boolean logMINOR;
	final BaseClientPutter parent;
	final InsertContext ctx;
	final PutCompletionCallback cb;
	final long dataLength;
	final short compressionCodec;
	final short splitfileAlgorithm;
	final int segmentSize;
	final int checkSegmentSize;
	final SplitFileInserterSegment[] segments;
	final boolean getCHKOnly;
	final int countCheckBlocks;
	final int countDataBlocks;
	private boolean haveSentMetadata;
	final ClientMetadata cm;
	final boolean isMetadata;
	private volatile boolean finished;
	private boolean fetchable;
	public final Object token;
	final boolean insertAsArchiveManifest;
	private boolean forceEncode;
	private final long decompressedLength;
	final boolean persistent;
	
	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.
	
	private final int hashCode;
	
	public int hashCode() {
		return hashCode;
	}

	public SimpleFieldSet getProgressFieldset() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		// don't save basic infrastructure such as ctx and parent
		// only save details of the request
		fs.putSingle("Type", "SplitFileInserter");
		fs.put("DataLength", dataLength);
		fs.put("DecompressedLength", decompressedLength);
		fs.put("CompressionCodec", compressionCodec);
		fs.put("SplitfileCodec", splitfileAlgorithm);
		fs.put("Finished", finished);
		fs.put("SegmentSize", segmentSize);
		fs.put("CheckSegmentSize", checkSegmentSize);
		SimpleFieldSet segs = new SimpleFieldSet(false);
		for(int i=0;i<segments.length;i++) {
			segs.put(Integer.toString(i), segments[i].getProgressFieldset());
		}
		segs.put("Count", segments.length);
		fs.put("Segments", segs);
		return fs;
	}

	public SplitFileInserter(BaseClientPutter put, PutCompletionCallback cb, Bucket data, Compressor bestCodec, long decompressedLength, ClientMetadata clientMetadata, InsertContext ctx, boolean getCHKOnly, boolean isMetadata, Object token, boolean insertAsArchiveManifest, boolean freeData, boolean persistent, ObjectContainer container, ClientContext context) throws InsertException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		hashCode = super.hashCode();
		this.parent = put;
		this.insertAsArchiveManifest = insertAsArchiveManifest;
		this.token = token;
		this.finished = false;
		this.isMetadata = isMetadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		this.decompressedLength = decompressedLength;
		this.dataLength = data.size();
		Bucket[] dataBuckets;
		try {
			dataBuckets = BucketTools.split(data, CHKBlock.DATA_LENGTH, ctx.persistentBucketFactory, freeData);
		} catch (IOException e) {
			throw new InsertException(InsertException.BUCKET_ERROR, e, null);
		}
		countDataBlocks = dataBuckets.length;
		// Encoding is done by segments
		if(bestCodec == null)
			compressionCodec = -1;
		else
			compressionCodec = bestCodec.codecNumberForMetadata();
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		segmentSize = ctx.splitfileSegmentDataBlocks;
		checkSegmentSize = splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT ? 0 : ctx.splitfileSegmentCheckBlocks;
		
		this.persistent = persistent;
		if(persistent) {
			container.activate(parent, 1);
		}
		
		// Create segments
		segments = splitIntoSegments(segmentSize, dataBuckets, context.mainExecutor, container, context);
		if(persistent) {
			// Deactivate all buckets, and let dataBuckets be GC'ed
			for(int i=0;i<dataBuckets.length;i++) {
				container.deactivate(dataBuckets[i], 5);
				dataBuckets[i] = null;
			}
		}
		dataBuckets = null;
		int count = 0;
		for(int i=0;i<segments.length;i++)
			count += segments[i].countCheckBlocks();
		countCheckBlocks = count;
		// Save progress to disk, don't want to do all that again (probably includes compression in caller)
		parent.onMajorProgress(container);
	}

	public SplitFileInserter(BaseClientPutter parent, PutCompletionCallback cb, ClientMetadata clientMetadata, InsertContext ctx, boolean getCHKOnly, boolean metadata, Object token, boolean insertAsArchiveManifest, SimpleFieldSet fs, ObjectContainer container, ClientContext context) throws ResumeException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		hashCode = super.hashCode();
		this.parent = parent;
		this.insertAsArchiveManifest = insertAsArchiveManifest;
		this.token = token;
		this.finished = false;
		this.isMetadata = metadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		this.persistent = parent.persistent();
		// Don't read finished, wait for the segmentFinished()'s.
		String length = fs.get("DataLength");
		if(length == null) throw new ResumeException("No DataLength");
		try {
			dataLength = Long.parseLong(length);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt DataLength: "+e+" : "+length);
		}
		length = fs.get("DecompressedLength");
		long dl = 0; // back compat
		if(length != null) {
			try {
				dl = Long.parseLong(length);
			} catch (NumberFormatException e) {
				dl = -1;
			}
		}
		decompressedLength = dl;
		String tmp = fs.get("SegmentSize");
		if(length == null) throw new ResumeException("No SegmentSize");
		try {
			segmentSize = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt SegmentSize: "+e+" : "+length);
		}
		tmp = fs.get("CheckSegmentSize");
		if(length == null) throw new ResumeException("No CheckSegmentSize");
		try {
			checkSegmentSize = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt CheckSegmentSize: "+e+" : "+length);
		}
		String ccodec = fs.get("CompressionCodec");
		if(ccodec == null) throw new ResumeException("No compression codec");
		try {
			compressionCodec = Short.parseShort(ccodec);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt CompressionCodec: "+e+" : "+ccodec);
		}
		String scodec = fs.get("SplitfileCodec");
		if(scodec == null) throw new ResumeException("No splitfile codec");
		try {
			splitfileAlgorithm = Short.parseShort(scodec);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt SplitfileCodec: "+e+" : "+scodec);
		}
		SimpleFieldSet segFS = fs.subset("Segments");
		if(segFS == null) throw new ResumeException("No segments");
		String segc = segFS.get("Count");
		if(segc == null) throw new ResumeException("No segment count");
		int segmentCount;
		try {
			segmentCount = Integer.parseInt(segc);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt segment count: "+e+" : "+segc);
		}
		segments = new SplitFileInserterSegment[segmentCount];
		
		int dataBlocks = 0;
		int checkBlocks = 0;
		
		for(int i=0;i<segments.length;i++) {
			String index = Integer.toString(i);
			SimpleFieldSet segment = segFS.subset(index);
			segFS.removeSubset(index);
			if(segment == null) throw new ResumeException("No segment "+i);
			segments[i] = new SplitFileInserterSegment(this, segment, splitfileAlgorithm, ctx, getCHKOnly, i, context, container);
			dataBlocks += segments[i].countDataBlocks();
			checkBlocks += segments[i].countCheckBlocks();
		}
		
		this.countDataBlocks = dataBlocks;
		this.countCheckBlocks = checkBlocks;
	}

	/**
	 * Group the blocks into segments.
	 */
	private SplitFileInserterSegment[] splitIntoSegments(int segmentSize, Bucket[] origDataBlocks, Executor executor, ObjectContainer container, ClientContext context) {
		int dataBlocks = origDataBlocks.length;

		Vector segs = new Vector();
		
		// First split the data up
		if((dataBlocks < segmentSize) || (segmentSize == -1)) {
			// Single segment
			FECCodec codec = FECCodec.getCodec(splitfileAlgorithm, origDataBlocks.length, executor);
			SplitFileInserterSegment onlySeg = new SplitFileInserterSegment(this, codec, origDataBlocks, ctx, getCHKOnly, 0, container);
			segs.add(onlySeg);
		} else {
			int j = 0;
			int segNo = 0;
			for(int i=segmentSize;;i+=segmentSize) {
				if(i > dataBlocks) i = dataBlocks;
				Bucket[] seg = new Bucket[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, i-j);
				j = i;
				for(int x=0;x<seg.length;x++)
					if(seg[x] == null) throw new NullPointerException("In splitIntoSegs: "+x+" is null of "+seg.length+" of "+segNo);
				FECCodec codec = FECCodec.getCodec(splitfileAlgorithm, seg.length, executor);
				SplitFileInserterSegment s = new SplitFileInserterSegment(this, codec, seg, ctx, getCHKOnly, segNo, container);
				segs.add(s);
				
				if(i == dataBlocks) break;
				segNo++;
			}
		}
		parent.notifyClients(container, context);
		return (SplitFileInserterSegment[]) segs.toArray(new SplitFileInserterSegment[segs.size()]);
	}
	
	public void start(ObjectContainer container, ClientContext context) throws InsertException {
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 2);
			segments[i].start(container, context);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
		if(persistent)
			container.activate(parent, 1);
		
		if(countDataBlocks > 32)
			parent.onMajorProgress(container);
		parent.notifyClients(container, context);
		
	}

	public void encodedSegment(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Encoded segment "+segment.segNo+" of "+this);
		boolean ret = false;
		boolean encode;
		synchronized(this) {
			encode = forceEncode;
			for(int i=0;i<segments.length;i++) {
				if(segments[i] != segment) {
					if(persistent)
						container.activate(segments[i], 1);
				}
				if((segments[i] == null) || !segments[i].isEncoded()) {
					ret = true;
					break;
				}
			}
		}
		if(encode) segment.forceEncode(container, context);
		if(ret) {
			for(int i=0;i<segments.length;i++) {
				if(segments[i] != segment)
					container.deactivate(segments[i], 1);
			}
			return;
		}
		if(persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(this, container, context);
		if(countDataBlocks > 32) {
			if(persistent)
				container.activate(parent, 1);
			parent.onMajorProgress(container);
		}
	}
	
	public void segmentHasURIs(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Segment has URIs: "+segment);
		synchronized(this) {
			if(haveSentMetadata) {
				return;
			}
			
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				if(!segments[i].hasURIs()) {
					if(logMINOR) Logger.minor(this, "Segment does not have URIs: "+segments[i]);
					if(persistent) {
						for(int j=0;j<i;j++) {
							if(segments[j] == segment) continue;
							container.deactivate(segments[i], 1);
						}
					}
					return;
				}
			}
		}
		
		if(logMINOR) Logger.minor(this, "Have URIs from all segments");
		encodeMetadata(container, context, segment);
	}
	
	private void encodeMetadata(ObjectContainer container, ClientContext context, SplitFileInserterSegment dontDeactivateSegment) {
		boolean missingURIs;
		Metadata m = null;
		if(persistent)
			activateSegments(container, dontDeactivateSegment);
		synchronized(this) {
			// Create metadata
			ClientCHK[] dataURIs = getDataCHKs(container);
			ClientCHK[] checkURIs = getCheckCHKs(container);
			
			if(logMINOR) Logger.minor(this, "Data URIs: "+dataURIs.length+", check URIs: "+checkURIs.length);
			
			missingURIs = anyNulls(dataURIs) || anyNulls(checkURIs);
			
			if(!missingURIs) {
				// Create Metadata
				m = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, cm, dataLength, compressionCodec, decompressedLength, isMetadata, insertAsArchiveManifest);
			}
			haveSentMetadata = true;
		}
		if(persistent)
			deactivateSegments(container, dontDeactivateSegment);
		if(missingURIs) {
			if(logMINOR) Logger.minor(this, "Missing URIs");
			// Error
			fail(new InsertException(InsertException.INTERNAL_ERROR, "Missing URIs after encoding", null), container, context);
			return;
		} else {
			if(persistent)
				container.activate(cb, 1);
			cb.onMetadata(m, this, container, context);
		}
	}
	
	private void activateSegments(ObjectContainer container, SplitFileInserterSegment notMe) {
		for(int i=0;i<segments.length;i++) {
			if(segments[i] == notMe) continue;
			container.activate(segments[i], 1);
		}
	}

	private void deactivateSegments(ObjectContainer container, SplitFileInserterSegment notMe) {
		for(int i=0;i<segments.length;i++) {
			if(segments[i] == notMe) continue;
			container.deactivate(segments[i], 1);
		}
	}

	private void fail(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent) {
			container.set(this);
			container.activate(cb, 1);
		}
		cb.onFailure(e, this, container, context);
	}

	// FIXME move this to somewhere
	private static boolean anyNulls(Object[] array) {
		for(int i=0;i<array.length;i++)
			if(array[i] == null) return true;
		return false;
	}

	/**
	 * Segments MUST BE ACTIVATED TO DEPTH 1 BEFORE CALLING,
	 * and should be deactivated by caller.
	 * @param container
	 * @return
	 */
	private ClientCHK[] getCheckCHKs(ObjectContainer container) {
		// Copy check blocks from each segment into a FreenetURI[].
		ClientCHK[] uris = new ClientCHK[countCheckBlocks];
		int x = 0;
		for(int i=0;i<segments.length;i++) {
			ClientCHK[] segURIs = segments[i].getCheckCHKs();
			if(x + segURIs.length > countCheckBlocks)
				throw new IllegalStateException("x="+x+", segURIs="+segURIs.length+", countCheckBlocks="+countCheckBlocks);
			System.arraycopy(segURIs, 0, uris, x, segURIs.length);
			x += segURIs.length;
		}

		if(persistent) {
			for(int i=0;i<uris.length;i++)
				container.activate(uris[i], 5);
		}
		
		if(uris.length != x)
			throw new IllegalStateException("Total is wrong");
		
		return uris;
	}

	/**
	 * Segments MUST BE ACTIVATED TO DEPTH 1 BEFORE CALLING,
	 * and should be deactivated by caller.
	 * @param container
	 * @return
	 */
	private ClientCHK[] getDataCHKs(ObjectContainer container) {
		// Copy check blocks from each segment into a FreenetURI[].
		ClientCHK[] uris = new ClientCHK[countDataBlocks];
		int x = 0;
		for(int i=0;i<segments.length;i++) {
			ClientCHK[] segURIs = segments[i].getDataCHKs();
			if(x + segURIs.length > countDataBlocks) 
				throw new IllegalStateException("x="+x+", segURIs="+segURIs.length+", countDataBlocks="+countDataBlocks);
			System.arraycopy(segURIs, 0, uris, x, segURIs.length);
			x += segURIs.length;
		}

		if(persistent) {
			for(int i=0;i<uris.length;i++)
				container.activate(uris[i], 5);
		}
		
		if(uris.length != x)
			throw new IllegalStateException("Total is wrong");
		
		return uris;
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void segmentFinished(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Segment finished: "+segment, new Exception("debug"));
		boolean allGone = true;
		if(countDataBlocks > 32) {
			if(persistent)
				container.activate(parent, 1);
			parent.onMajorProgress(container);
		}
		synchronized(this) {
			if(finished) {
				if(logMINOR) Logger.minor(this, "Finished already");
				return;
			}
			for(int i=0;i<segments.length;i++) {
				if(persistent && segments[i] != segment)
					container.activate(segments[i], 1);
				if(!segments[i].isFinished()) {
					if(logMINOR) Logger.minor(this, "Segment not finished: "+i+": "+segments[i]);
					allGone = false;
					if(persistent) {
						for(int j=0;j<=i;j++) {
							if(segments[j] == segment) continue;
							container.deactivate(segments[i], 1);
						}
					}
					break;
				}
			}
			
			InsertException e = segment.getException();
			if((e != null) && e.isFatal()) {
				cancel(container, context);
			} else {
				if(!allGone) return;
			}
			finished = true;
		}
		if(persistent)
			container.set(this);
		onAllFinished(container, context);
	}
	
	public void segmentFetchable(SplitFileInserterSegment segment, ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Segment fetchable: "+segment);
		synchronized(this) {
			if(finished) return;
			if(fetchable) return;
			for(int i=0;i<segments.length;i++) {
				if(persistent && segments[i] != segment)
					container.activate(segments[i], 1);
				if(!segments[i].isFetchable()) {
					if(logMINOR) Logger.minor(this, "Segment not fetchable: "+i+": "+segments[i]);
					if(persistent) {
						for(int j=0;j<=i;j++) {
							if(segments[j] == segment) continue;
							container.deactivate(segments[i], 1);
						}
					}
					return;
				}
			}
			fetchable = true;
		}
		if(persistent) {
			container.activate(cb, 1);
			container.set(this);
		}
		cb.onFetchable(this, container);
	}

	private void onAllFinished(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "All finished");
		try {
			// Finished !!
			FailureCodeTracker tracker = new FailureCodeTracker(true);
			boolean allSucceeded = true;
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				InsertException e = segments[i].getException();
				if(e == null) continue;
				if(logMINOR) Logger.minor(this, "Failure on segment "+i+" : "+segments[i]+" : "+e, e);
				allSucceeded = false;
				if(e.errorCodes != null)
					tracker.merge(e.errorCodes);
				tracker.inc(e.getMode());
			}
			if(persistent)
				container.activate(cb, 1);
			if(allSucceeded)
				cb.onSuccess(this, container, context);
			else {
				cb.onFailure(InsertException.construct(tracker), this, container, context);
			}
		} catch (Throwable t) {
			// We MUST tell the parent *something*!
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR), this, container, context);
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent)
			container.set(this);
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].cancel(container, context);
		}
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(container, context);
	}

	public Object getToken() {
		return token;
	}

	public long getLength() {
		return dataLength;
	}

	/** Force the remaining blocks which haven't been encoded so far to be encoded ASAP. */
	public void forceEncode(ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		Logger.minor(this, "Forcing encode on "+this);
		synchronized(this) {
			forceEncode = true;
		}
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].forceEncode(container, context);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
	}

}
