package de.schildbach.pte;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Point;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsContext;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.ResultHeader;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.SuggestLocationsResult;
import de.schildbach.pte.dto.SuggestedLocation;
import de.schildbach.pte.exception.InvalidDataException;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.HttpClient;
import de.schildbach.pte.util.ParserUtils;
import de.schildbach.pte.util.XmlPullUtil;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;

public class SkanetrafikenProvider extends AbstractNetworkProvider {

	public static final TimeZone TZ = TimeZone.getTimeZone("Europe/Stockholm");
	private final XmlPullParserFactory parserFactory;

	public SkanetrafikenProvider() {
		super(NetworkId.SKANETRAFIKEN);
		try {
			String factoryName = System.getProperty(XmlPullParserFactory.PROPERTY_NAME);
			parserFactory = XmlPullParserFactory.newInstance(factoryName, null);
		} catch(final XmlPullParserException x) {
			throw new RuntimeException(x);
		}
	}

	@Override
	protected boolean hasCapability(Capability capability) {
		return true;
	}

	@Override
	public NearbyLocationsResult queryNearbyLocations(EnumSet<LocationType> types, Location location,
	                                                  int maxDistance, final int maxLocations) throws IOException {
		boolean wantsStations = types.contains(LocationType.STATION)
				|| types.contains(LocationType.ANY);
		if(location.hasLocation() && wantsStations) {
			return queryNearbyByCoord(location, maxDistance, maxLocations);
		} else {
			return new NearbyLocationsResult(header(), NearbyLocationsResult.Status.OK);
		}
	}

	@Override
	public SuggestLocationsResult suggestLocations(CharSequence constraint) throws IOException {
		HttpUrl url = buildURL("querypage.asp")
				.addQueryParameter("inpPointFr", constraint.toString())
				.addQueryParameter("inpPointTo", constraint.toString())
				.build();

		final List<SuggestedLocation> locations = new ArrayList<>();
		httpClient.getInputStream(new HttpClient.Callback() {
			@Override
			public void onSuccessful(CharSequence bodyPeek, ResponseBody body) throws IOException {
				try {
					XmlPullParser pp = parseResponse(body);
					XmlPullUtil.enter(pp, "StartPoints");
					int priority = Integer.MAX_VALUE;
					while(XmlPullUtil.optEnter(pp, "Point")) {
						locations.add(new SuggestedLocation(parseLocation(pp, LocationType.ANY), priority--));
						XmlPullUtil.skipExit(pp, "Point");
					}
				} catch(XmlPullParserException e) {
					throw new ParserException(e);
				}
			}
		}, url);
		return new SuggestLocationsResult(header(), locations);
	}

	private NearbyLocationsResult queryNearbyByCoord(Location location, int maxDistance, final int maxLocations)
			throws IOException {
		Point rt90 = RT90.fromWGS84(location.lat, location.lon);

		HttpUrl.Builder url = buildURL("neareststation.asp")
				.addQueryParameter("x", Integer.toString(rt90.lat))
				.addQueryParameter("y", Integer.toString(rt90.lon));
		if(maxDistance > 0) {
			url.addQueryParameter("radius", Integer.toString(maxDistance));
		}

		final List<Location> locations = new ArrayList<>();
		httpClient.getInputStream(new HttpClient.Callback() {
			@Override
			public void onSuccessful(CharSequence bodyPeek, ResponseBody body) throws IOException {
				try {
					XmlPullParser pp = parseResponse(body);
					XmlPullUtil.enter(pp, "NearestStopAreas");
					int max = maxLocations > 0 ? maxLocations : Integer.MAX_VALUE;
					for(int i = 0; i < max && XmlPullUtil.optEnter(pp, "NearestStopArea"); i++) {
						Location l = parseLocation(pp, LocationType.STATION);
						locations.add(l);

						XmlPullUtil.skipExit(pp, "NearestStopArea");
					}
				} catch(XmlPullParserException e) {
					throw new ParserException(e);
				}
			}
		}, url.build());
		return new NearbyLocationsResult(header(), locations);
	}

	private ResultHeader header() {
		return new ResultHeader(network, network.name());
	}

	private Location parseLocation(XmlPullParser pp, LocationType defaultType)
			throws XmlPullParserException, IOException {
		String id = XmlPullUtil.valueTag(pp, "Id");
		String name = XmlPullUtil.valueTag(pp, "Name");
		LocationType type = locationType(pp, defaultType);
		if(type != LocationType.STATION) {
			id = null; // only station should have IDs
		}
		int x = parseCoord(pp, "X");
		int y = parseCoord(pp, "Y");
		return new Location(type, id, RT90.toWGS84(x, y), null, name, null);
	}

	private Set<Product> productsAt(String id) throws IOException {
		HttpUrl url = buildURL("stationresults.asp")
				.addQueryParameter("selPointFrKey", id)
				.build();
		final Set<Product> products = new HashSet<>();
		httpClient.getInputStream(new HttpClient.Callback() {
			@Override
			public void onSuccessful(CharSequence bodyPeek, ResponseBody body) throws IOException {
				try {
					XmlPullParser pp = parseResponse(body);
					if(!XmlPullUtil.optEnter(pp, "Lines")) {
						return;
					}
					while(XmlPullUtil.optEnter(pp, "Line")) {
						XmlPullUtil.skipUntil(pp, "LineTypeName");
						String typeName = XmlPullUtil.valueTag(pp, "LineTypeName");
						Product product = parseProduct(typeName);
						if(product != null) {
							products.add(product);
						}

						XmlPullUtil.skipExit(pp, "Line");
					}
				} catch(XmlPullParserException e) {
					throw new ParserException(e);
				}
			}
		}, url);
		return products;
	}

	private static Product parseProduct(String type) {
		switch(type) {
			case "1": // Regionbuss (regional bus)
			case "2": // SkåneExpress (regional bus)
			case "4": // Stadsbuss (city bus)
			case "256": // Flygbuss (airport bus)
				return Product.BUS;
			case "32": // Pågatåg
			case "128": // Öresundståg
				return Product.REGIONAL_TRAIN;
			case "1024": // Närtrafik (pre-booked trips on places without bus lines)
				return Product.ON_DEMAND;
		}
		return null;
	}

	private static LocationType locationType(XmlPullParser pp, LocationType defaultType)
			throws IOException, XmlPullParserException {
		String type = XmlPullUtil.optValueTag(pp, "Type", null);
		return locationType(type, defaultType);
	}

	private static LocationType locationType(String type, LocationType defaultType) {
		if(type != null && !type.isEmpty()) {
			switch(type) {
				case "STOP_AREA":
					return LocationType.STATION;
				case "ADDRESS":
					return LocationType.ADDRESS;
				case "POI":
					return LocationType.POI;
				default:
					return LocationType.ANY;
			}
		}
		return defaultType;
	}

	private static int parseCoord(XmlPullParser pp, String tagName) throws IOException, XmlPullParserException {
		String text = XmlPullUtil.valueTag(pp, tagName);
		if(text != null && !text.isEmpty()) {
			try {
				return Integer.parseInt(text);
			} catch(NullPointerException | IllegalArgumentException ignored) {
				// fall through
			}
		}
		return 0;
	}

	@Override
	public QueryDeparturesResult queryDepartures(final String stationId, @Nullable Date time, final int maxDepartures, boolean equivs) throws IOException {
		HttpUrl.Builder url = buildURL("stationresults.asp")
				.addQueryParameter("selPointFrKey", stationId)
				.addQueryParameter("selDirection", "0");
		if(time != null) {
			Calendar cal = new GregorianCalendar(TZ);
			cal.setTime(time);
			url.addQueryParameter("inpDate", String.format(Locale.ROOT,
					"%04d-%02d-%02d",
					cal.get(Calendar.YEAR),
					cal.get(Calendar.MONTH) + 1,
					cal.get(Calendar.DAY_OF_MONTH)));
			url.addQueryParameter("inpTime", String.format(Locale.ROOT,
					"%02d:%02d:%02d",
					cal.get(Calendar.HOUR_OF_DAY),
					cal.get(Calendar.MINUTE),
					cal.get(Calendar.SECOND)));
		}

		final List<Departure> departures = new ArrayList<>();
		try {
			httpClient.getInputStream(new HttpClient.Callback() {
				@Override
				public void onSuccessful(CharSequence bodyPeek, ResponseBody body) throws IOException {
					try {
						XmlPullParser pp = parseResponse(body);
						if(!XmlPullUtil.optEnter(pp, "Lines")) {
							return;
						}
						while(XmlPullUtil.optEnter(pp, "Line")
								&& departures.size() < maxDepartures) {
							String name = XmlPullUtil.valueTag(pp, "Name");
							String number = XmlPullUtil.valueTag(pp, "No");
							String dateTime = XmlPullUtil.valueTag(pp, "JourneyDateTime");
							XmlPullUtil.skipUntil(pp, "StopPoint");
							String stopPoint = XmlPullUtil.optValueTag(pp, "StopPoint", null);
							if(pp.getEventType() == XmlPullParser.END_TAG) {
								pp.next();
							}
							String type = XmlPullUtil.valueTag(pp, "LineTypeId");
							String typeName = XmlPullUtil.valueTag(pp, "LineTypeName");
							String towards = XmlPullUtil.valueTag(pp, "Towards");
							XmlPullUtil.skipUntil(pp, "TrainNo");
							String trainNumber = XmlPullUtil.valueTag(pp, "TrainNo");
							if(trainNumber != null && !"0".equals(trainNumber)) {
								number = trainNumber;
							}

							Date planned = parseTimestamp(dateTime);
							Product product = parseProduct(type);

							String label;
							if(name == null || name.equals(number)) {
								label = typeName + " " + number;
							} else if(isNumber(name)) {
								label = typeName + " " + name;
							} else if(Character.isDigit(name.charAt(name.length() - 1))) {
								// line number is already present on the name
								label = name;
							} else {
								label = name + " " + number;
							}

							String message = null;

							Departure departure = new Departure(
									planned, null,
									new Line(null, null, product, label),
									stopPoint != null ? new Position(stopPoint) : null,
									new Location(LocationType.STATION, null, null, towards),
									null, message);
							departures.add(departure);

							XmlPullUtil.skipExit(pp, "Line");
						}
					} catch(XmlPullParserException e) {
						throw new ParserException(e);
					}
				}
			}, url.build());

			QueryDeparturesResult result = new QueryDeparturesResult(header());
			result.stationDepartures.add(new StationDepartures(
					new Location(LocationType.STATION, stationId),
					departures, null));
			return result;
		} catch(InvalidDataException ignore) {
			return new QueryDeparturesResult(header(), QueryDeparturesResult.Status.INVALID_STATION);
		}
	}

	private static boolean isNumber(String text) {
		try {
			return Long.parseLong(text) >= 0;
		} catch(Exception ignore) {
			return false;
		}
	}

	private static Date parseTimestamp(String timestamp) {
		String[] parts = timestamp.split("T");
		GregorianCalendar cal = new GregorianCalendar(TZ);
		ParserUtils.parseIsoDate(cal, parts[0]);
		ParserUtils.parseEuropeanTime(cal, parts[1]);
		return cal.getTime();
	}

	@Override
	public QueryTripsResult queryTrips(Location from, @Nullable Location via, Location to, Date date, boolean dep, @Nullable Set<Product> products, @Nullable Optimize optimize, @Nullable WalkSpeed walkSpeed, @Nullable Accessibility accessibility, @Nullable Set<Option> options) throws IOException {
		return null;
	}

	@Override
	public QueryTripsResult queryMoreTrips(QueryTripsContext context, boolean later) throws IOException {
		return null;
	}

	private static HttpUrl.Builder buildURL(String path) {
		return new HttpUrl.Builder()
				.scheme("http")
				.host("www.labs.skanetrafiken.se")
				.addPathSegment("/v2.2")
				.addPathSegment(path);
	}

	private XmlPullParser parseResponse(ResponseBody body) throws IOException {
		try {
			XmlPullParser pp = parserFactory.newPullParser();
			pp.setInput(body.charStream());
			if(pp.getEventType() != XmlPullParser.START_DOCUMENT) {
				throw new ParserException("Expecting start of document");
			}
			XmlPullUtil.enter(pp, "soap:Envelope");
			XmlPullUtil.enter(pp, "soap:Body");
			XmlPullUtil.enter(pp); // response
			XmlPullUtil.enter(pp); // result
			String code = XmlPullUtil.valueTag(pp, "Code");
			String message = XmlPullUtil.optValueTag(pp, "Message", null);
			if(!"0".equals(code)) {
				if(message != null && !message.isEmpty()) {
					throw new InvalidDataException(code + ": " + message);
				} else {
					throw new InvalidDataException(code);
				}
			}
			XmlPullUtil.exit(pp, "Message");
			return pp;
		} catch(XmlPullParserException e) {
			throw new ParserException(e);
		}
	}

	public static class RT90 {
		private static final double axis = 6378137.0; // GRS 80.
		private static final double flattening = 1.0 / 298.257222101; // GRS 80.
		private static final double central_meridian = 15.0 + 48.0 / 60.0 + 22.624306 / 3600.0;
		private static final double scale = 1.00000561024;
		private static final double false_northing = -667.711;
		private static final double false_easting = 1500064.274;

		private static final double e2 = flattening * (2.0 - flattening);
		private static final double n = flattening / (2.0 - flattening);
		private static final double a_roof = axis / (1.0 + n) * (1.0 + n * n / 4.0 + n * n * n * n / 64.0);
		private static final double delta1 = n / 2.0 - 2.0 * n * n / 3.0 + 37.0 * n * n * n / 96.0 - n * n * n * n / 360.0;
		private static final double delta2 = n * n / 48.0 + n * n * n / 15.0 - 437.0 * n * n * n * n / 1440.0;
		private static final double delta3 = 17.0 * n * n * n / 480.0 - 37 * n * n * n * n / 840.0;
		private static final double delta4 = 4397.0 * n * n * n * n / 161280.0;

		private static final double Astar = e2 + e2 * e2 + e2 * e2 * e2 + e2 * e2 * e2 * e2;
		private static final double Bstar = -(7.0 * e2 * e2 + 17.0 * e2 * e2 * e2 + 30.0 * e2 * e2 * e2 * e2) / 6.0;
		private static final double Cstar = (224.0 * e2 * e2 * e2 + 889.0 * e2 * e2 * e2 * e2) / 120.0;
		private static final double Dstar = -(4279.0 * e2 * e2 * e2 * e2) / 1260.0;
		private static final double deg_to_rad = Math.PI / 180;
		private static final double lambda_zero = central_meridian * deg_to_rad;

		private static final double A = e2;
		private static final double B = (5.0 * e2 * e2 - e2 * e2 * e2) / 6.0;
		private static final double C = (104.0 * e2 * e2 * e2 - 45.0 * e2 * e2 * e2 * e2) / 120.0;
		private static final double D = (1237.0 * e2 * e2 * e2 * e2) / 1260.0;
		private static final double beta1 = n / 2.0 - 2.0 * n * n / 3.0 + 5.0 * n * n * n / 16.0 + 41.0 * n * n * n * n / 180.0;
		private static final double beta2 = 13.0 * n * n / 48.0 - 3.0 * n * n * n / 5.0 + 557.0 * n * n * n * n / 1440.0;
		private static final double beta3 = 61.0 * n * n * n / 240.0 - 103.0 * n * n * n * n / 140.0;
		private static final double beta4 = 49561.0 * n * n * n * n / 161280.0;

		public static Point toWGS84(int x, int y) {
			double xi = (x - false_northing) / (scale * a_roof);
			double eta = (y - false_easting) / (scale * a_roof);
			double xi_prim = xi -
					delta1 * Math.sin(2.0 * xi) * Math.cosh(2.0 * eta) -
					delta2 * Math.sin(4.0 * xi) * Math.cosh(4.0 * eta) -
					delta3 * Math.sin(6.0 * xi) * Math.cosh(6.0 * eta) -
					delta4 * Math.sin(8.0 * xi) * Math.cosh(8.0 * eta);
			double eta_prim = eta -
					delta1 * Math.cos(2.0 * xi) * Math.sinh(2.0 * eta) -
					delta2 * Math.cos(4.0 * xi) * Math.sinh(4.0 * eta) -
					delta3 * Math.cos(6.0 * xi) * Math.sinh(6.0 * eta) -
					delta4 * Math.cos(8.0 * xi) * Math.sinh(8.0 * eta);
			double phi_star = Math.asin(Math.sin(xi_prim) / Math.cosh(eta_prim));
			double delta_lambda = Math.atan(Math.sinh(eta_prim) / Math.cos(xi_prim));
			double lon_radian = lambda_zero + delta_lambda;
			double lat_radian = phi_star + Math.sin(phi_star) * Math.cos(phi_star) *
					(Astar + Bstar * Math.pow(Math.sin(phi_star), 2)
							+ Cstar * Math.pow(Math.sin(phi_star), 4)
							+ Dstar * Math.pow(Math.sin(phi_star), 6));

			double lat = lat_radian * 180.0 / Math.PI;
			double lon = lon_radian * 180.0 / Math.PI;
			return Point.fromDouble(lat, lon);
		}

		public static Point fromWGS84(int lat, int lon) {
			double phi = (lat / 1E6) * deg_to_rad;
			double lambda = (lon / 1E6) * deg_to_rad;
			double phi_star = phi - Math.sin(phi) * Math.cos(phi) * (A +
					B * Math.pow(Math.sin(phi), 2) +
					C * Math.pow(Math.sin(phi), 4) +
					D * Math.pow(Math.sin(phi), 6));
			double delta_lambda = lambda - lambda_zero;
			double xi_prim = Math.atan(Math.tan(phi_star) / Math.cos(delta_lambda));
			double eta_prim = atanh(Math.cos(phi_star) * Math.sin(delta_lambda));
			double x = scale * a_roof * (xi_prim +
					beta1 * Math.sin(2.0 * xi_prim) * Math.cosh(2.0 * eta_prim) +
					beta2 * Math.sin(4.0 * xi_prim) * Math.cosh(4.0 * eta_prim) +
					beta3 * Math.sin(6.0 * xi_prim) * Math.cosh(6.0 * eta_prim) +
					beta4 * Math.sin(8.0 * xi_prim) * Math.cosh(8.0 * eta_prim)) +
					false_northing;
			double y = scale * a_roof * (eta_prim +
					beta1 * Math.cos(2.0 * xi_prim) * Math.sinh(2.0 * eta_prim) +
					beta2 * Math.cos(4.0 * xi_prim) * Math.sinh(4.0 * eta_prim) +
					beta3 * Math.cos(6.0 * xi_prim) * Math.sinh(6.0 * eta_prim) +
					beta4 * Math.cos(8.0 * xi_prim) * Math.sinh(8.0 * eta_prim)) +
					false_easting;

			x = Math.round(x * 1000.0) / 1000.0;
			y = Math.round(y * 1000.0) / 1000.0;
			return new Point((int) x, (int) y);
		}

		private static double atanh(double value) {
			return 0.5 * Math.log((1.0 + value) / (1.0 - value));
		}
	}
}
