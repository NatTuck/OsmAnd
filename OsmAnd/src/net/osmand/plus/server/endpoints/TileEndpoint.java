package net.osmand.plus.server.endpoints;

import android.graphics.Bitmap;
import fi.iki.elonen.NanoHTTPD;
import net.osmand.PlatformUtil;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.resources.AsyncLoadingThread;
import net.osmand.plus.server.MetaTileFileSystemCache;
import net.osmand.plus.server.OsmAndHttpServer;
import net.osmand.util.MapUtils;
import org.apache.commons.logging.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

public class TileEndpoint implements OsmAndHttpServer.ApiEndpoint {
	private static final int TILE_SIZE_PX = 256;
	private static final int TILE_DENSITY = 2;
	private static final int TIMEOUT_STEP = 500;
	private static final int TIMEOUT = 5000;
	private static final int METATILE_SIZE = 2;

	private static final Log LOG = PlatformUtil.getLog(TileEndpoint.class);
	private final MapActivity mapActivity;
	private final MetaTileFileSystemCache cache;

	public static class MetaTileCache {
		Bitmap bmp;
		int sx;
		int sy;
		int ex;
		int ey;
		int zoom;

		public MetaTileCache() {

		}

		public MetaTileCache(Bitmap bmp, int sx, int sy, int ex, int ey, int zoom) {
			this.bmp = bmp;
			this.sx = sx;
			this.sy = sy;
			this.ex = ex;
			this.ey = ey;
			this.zoom = zoom;
		}

		// to be used in file name
		public String getTileId() {
			return zoom + "_" + METATILE_SIZE + "_" + sx + "_" + sy;
		}

		public Bitmap getBitmap() {
			return bmp;
		}

		public Bitmap getSubtile(int x, int y) {
			return Bitmap.createBitmap(bmp,
					(x - sx) * TILE_SIZE_PX * TILE_DENSITY,
					(y - sy) * TILE_SIZE_PX * TILE_DENSITY,
					TILE_SIZE_PX * TILE_DENSITY, TILE_SIZE_PX * TILE_DENSITY);
		}
	}

	public TileEndpoint(MapActivity mapActivity) {
		this.mapActivity = mapActivity;
		this.cache = new MetaTileFileSystemCache(mapActivity.getMyApplication());
	}

	@Override
	public NanoHTTPD.Response process(NanoHTTPD.IHTTPSession session, String url) {
		// https://tile.osmand.net/hd/6/55/25.png
		int extInd = url.indexOf('.');
		if (extInd >= 0) {
			url = url.substring(0, extInd);
		}
		if (url.charAt(0) == '/') {
			url = url.substring(1);
		}
		String[] prms = url.split("/");
		if (prms.length < 4) {
			return OsmAndHttpServer.ErrorResponses.response500;
		}
		int zoom = Integer.parseInt(prms[1]);
		int x = Integer.parseInt(prms[2]);
		int y = Integer.parseInt(prms[3]);
		MetaTileCache res = cache.get(zoom, METATILE_SIZE, x, y);
		if (res == null) {
			res = requestMetatile(x, y, zoom);
			if (res == null) {
				LOG.error("SERVER: Cannot request metatile");
				return OsmAndHttpServer.ErrorResponses.response500;
			}
		}
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		Bitmap bmp = res.getSubtile(x, y);
		if (bmp == null) {
			LOG.error("SERVER: Cannot cut bitmap");
			return OsmAndHttpServer.ErrorResponses.response500;
		}
		bmp.compress(Bitmap.CompressFormat.PNG, 100, stream);
		byte[] byteArray = stream.toByteArray();
		ByteArrayInputStream str = new ByteArrayInputStream(byteArray);
		return newFixedLengthResponse(
				NanoHTTPD.Response.Status.OK, "image/png",
				str, str.available());
	}

	private synchronized MetaTileCache requestMetatile(int x, int y, int zoom) {
		int mx = (x / METATILE_SIZE) * METATILE_SIZE;
		int my = (y / METATILE_SIZE) * METATILE_SIZE;
		double lat = MapUtils.getLatitudeFromTile(zoom, my + 0.5 * METATILE_SIZE);
		double lon = MapUtils.getLongitudeFromTile(zoom, mx + 0.5 * METATILE_SIZE);
		final RotatedTileBox cp = mapActivity.getMapView().getCurrentRotatedTileBox();
		final RotatedTileBox rotatedTileBox = new RotatedTileBox.RotatedTileBoxBuilder()
				.setLocation(lat, lon)
				.setMapDensity(TILE_DENSITY).density(TILE_DENSITY)
				.setZoom(zoom)
				.setPixelDimensions(TILE_SIZE_PX * TILE_DENSITY * METATILE_SIZE,
						TILE_SIZE_PX * TILE_DENSITY * METATILE_SIZE, 0.5f, 0.5f).build();
		mapActivity.getMapView().setCurrentViewport(rotatedTileBox);
		int timeout = 0;
		try {
			AsyncLoadingThread athread = mapActivity.getMyApplication().getResourceManager().getAsyncLoadingThread();
			Thread.sleep(TIMEOUT_STEP); // TODO line should be removed in future
			MetaTileCache res = null;
			while (athread.areResourcesLoading() && timeout < TIMEOUT) {
				Thread.sleep(TIMEOUT_STEP);
				timeout += TIMEOUT_STEP;
			}
			if (!athread.areResourcesLoading()) {
				res = new MetaTileCache();
				res.sx = mx;
				res.ex = mx + METATILE_SIZE - 1;
				res.sy = my;
				res.ey = my + METATILE_SIZE - 1;
				res.zoom = zoom;
				Bitmap tempBmp = mapActivity.getMapView().getBufferBitmap();
				res.bmp = tempBmp.copy(tempBmp.getConfig(), true);
				cache.put(res);
			}
			return res;
		} catch (InterruptedException e) {
			LOG.error(e);
		}
		return null;
	}
}