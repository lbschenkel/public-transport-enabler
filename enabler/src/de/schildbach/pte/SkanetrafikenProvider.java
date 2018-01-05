package de.schildbach.pte;

import de.schildbach.pte.dto.*;
import de.schildbach.pte.exception.ParserException;
import de.schildbach.pte.util.HttpClient;
import de.schildbach.pte.util.XmlPullUtil;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

public class SkanetrafikenProvider extends AbstractNetworkProvider {

    private final XmlPullParserFactory parserFactory;

    public SkanetrafikenProvider() {
        super(NetworkId.SKANETRAFIKEN);
        try {
            String factoryName = System.getProperty(XmlPullParserFactory.PROPERTY_NAME);
            parserFactory = XmlPullParserFactory.newInstance(factoryName, null);
        } catch (final XmlPullParserException x) {
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
        if (location.type == LocationType.COORD) {
            return queryNearbyByCoord(location, maxDistance, maxLocations);
        } else {
            throw new UnsupportedOperationException();
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
                    while (XmlPullUtil.optEnter(pp, "Point")) {
                        locations.add(new SuggestedLocation(parseLocation(pp, LocationType.ANY)));
                        XmlPullUtil.skipExit(pp, "Point");
                    }
                } catch (XmlPullParserException e) {
                    throw new ParserException(e);
                }
            }
        }, url);
        return new SuggestLocationsResult(header(), locations);
    }

    private NearbyLocationsResult queryNearbyByCoord(Location location, int maxDistance, final int maxLocations)
            throws IOException {
        // FIXME: convert from WGS84
        int lat = location.lat;
        int lon = location.lon;

        HttpUrl.Builder url = buildURL("neareststation.asp")
                .addQueryParameter("x", Integer.toString(lat))
                .addQueryParameter("y", Integer.toString(lon));
        if (maxDistance > 0) {
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
                    for (int i = 0; i < max && XmlPullUtil.optEnter(pp, "NearestStopArea"); i++) {
                        locations.add(parseLocation(pp, LocationType.STATION));
                        XmlPullUtil.skipExit(pp, "NearestStopArea");
                    }
                } catch (XmlPullParserException e) {
                    throw new ParserException(e);
                }
            }
        }, url.build());
        return new NearbyLocationsResult(header(), locations);
    }

    private ResultHeader header() {
        return new ResultHeader(network, network.name());
    }

    private static Location parseLocation(XmlPullParser pp, LocationType defaultType)
            throws XmlPullParserException, IOException {
        String id = XmlPullUtil.valueTag(pp, "Id");
        String name = XmlPullUtil.valueTag(pp, "Name");
        LocationType type = locationType(pp, defaultType);
        int x = parseCoord(pp, "X");
        int y = parseCoord(pp, "Y");
        return new Location(type, id, x, y, null, name);
    }

    private static LocationType locationType(XmlPullParser pp, LocationType defaultType)
            throws IOException, XmlPullParserException {
        String type = XmlPullUtil.optValueTag(pp, "Type", null);
        return locationType(type, defaultType);
    }

    private static LocationType locationType(String type, LocationType defaultType) {
        if (type != null && !type.isEmpty()) {
            switch (type) {
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
        if (text != null && !text.isEmpty()) {
            try {
                return Integer.parseInt(text);
            } catch (NullPointerException | IllegalArgumentException ignored) {
                // fall through
            }
        }
        return 0;
    }

    @Override
    public QueryDeparturesResult queryDepartures(String stationId, @Nullable Date time, int maxDepartures, boolean equivs) throws IOException {
        return null;
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
        return HttpUrl.parse("http://www.labs.skanetrafiken.se/v2.2/").newBuilder().addPathSegment(path);
    }

    private XmlPullParser parseResponse(ResponseBody body) throws IOException {
        try {
            XmlPullParser pp = parserFactory.newPullParser();
            pp.setInput(body.charStream());
            if (pp.getEventType() != XmlPullParser.START_DOCUMENT) {
                throw new ParserException("Expecting start of document");
            }
            XmlPullUtil.enter(pp, "soap:Envelope");
            XmlPullUtil.enter(pp, "soap:Body");
            XmlPullUtil.enter(pp); // response
            XmlPullUtil.enter(pp); // result
            String code = XmlPullUtil.valueTag(pp, "Code");
            String message = XmlPullUtil.optValueTag(pp, "Message", null);
            XmlPullUtil.exit(pp, "Message");
            if (!"0".equals(code)) {
                if (message != null && !message.isEmpty()) {
                    throw new ParserException("Error " + code + ": " + message);
                } else {
                    throw new ParserException("Error " + code);
                }
            }
            return pp;
        } catch (XmlPullParserException e) {
            throw new ParserException(e);
        }
    }

}
