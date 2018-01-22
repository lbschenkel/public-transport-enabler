/*
 * Copyright 2010-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.pte.live;

import org.junit.Test;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.List;

import de.schildbach.pte.NetworkProvider.Accessibility;
import de.schildbach.pte.NetworkProvider.WalkSpeed;
import de.schildbach.pte.SkanetrafikenProvider;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyLocationsResult;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.StationDepartures;
import de.schildbach.pte.dto.SuggestLocationsResult;

import static java.util.Calendar.DATE;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Andreas Schildbach
 */
public class SkanetrafikenProviderLiveTest extends AbstractProviderLiveTest {
	public SkanetrafikenProviderLiveTest() {
		super(new SkanetrafikenProvider());
	}

	@Test
	public void suggestLocationsProvidesStation() throws Exception {
		final SuggestLocationsResult result = suggestLocations("Malmö C");
		print(result);

		assertEquals("Location{STATION, 80000, 55608777/13000200, name=Malmö C}",
				findFirst(LocationType.STATION, result.getLocations()).toString());
	}

	@Test
	public void suggestLocationsProvidesAddress() throws Exception {
		final SuggestLocationsResult result = suggestLocations("Scaniaplatsen");
		print(result);

		assertEquals("Location{ADDRESS, 55612467/12972328, name=Scaniaplatsen 1 Malmö}",
				findFirst(LocationType.ADDRESS, result.getLocations()).toString());
	}

	@Test
	public void suggestLocationsProvidesPOI() throws Exception {
		final SuggestLocationsResult result = suggestLocations("Emporia");
		print(result);

		assertEquals("Location{POI, 55564469/12972933, name=Emporia}",
				findFirst(LocationType.POI, result.getLocations()).toString());
	}

	@Test
	public void nearbyStationsByCoordinate() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		final NearbyLocationsResult result = queryNearbyStations(coord);
		print(result);

		assertEquals("Location{STATION, 80110, 55605543/13023098, name=Malmö Värnhem}",
				findFirst(LocationType.STATION, result.locations).toString());
	}

	@Test
	public void nearbyStationsWithoutCoordinate() throws Exception {
		Location station = new Location(LocationType.STATION, "7414867");
		final NearbyLocationsResult result = queryNearbyStations(station);
		print(result);

		assertEquals(NearbyLocationsResult.Status.OK, result.status);
		assertEquals("API can only search using coordinates",
				null, result.locations);
	}

	@Test
	public void nearbyLocationsRequestingStation() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		EnumSet<LocationType> types = EnumSet.of(LocationType.STATION);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord);
		print(result);

		assertEquals("Location{STATION, 80110, 55605543/13023098, name=Malmö Värnhem}",
				findFirst(LocationType.STATION, result.locations).toString());
	}

	@Test
	public void nearbyLocationsRequestingAny() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		EnumSet<LocationType> types = EnumSet.of(LocationType.ANY);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord);
		print(result);

		assertEquals("Location{STATION, 80110, 55605543/13023098, name=Malmö Värnhem}",
				findFirst(LocationType.STATION, result.locations).toString());
	}

	@Test
	public void nearbyLocationsWithoutRequestingStations() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		EnumSet<LocationType> types = EnumSet.allOf(LocationType.class);
		types.remove(LocationType.ANY);
		types.remove(LocationType.STATION);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord);
		print(result);

		assertEquals(NearbyLocationsResult.Status.OK, result.status);
		assertEquals("API can only search for stations",
				null, result.locations);
	}

	@Test
	public void nearbyLocationsShouldIncludeNearby() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		EnumSet<LocationType> types = EnumSet.of(LocationType.ANY);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord);
		print(result);

		assertThat("Should include Ellstorp, ~400m from Värnhem",
				result.locations, hasItem(new Location(LocationType.STATION, "80129")));
	}

	@Test
	public void nearbyLocationsShouldLimitRadius() throws Exception {
		Location coord = Location.coord(55605792, 13023055);
		EnumSet<LocationType> types = EnumSet.of(LocationType.ANY);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord, 300, 0);
		print(result);

		assertThat("Should not include Ellstorp, > 300m from Värnhem",
				result.locations, not(hasItem(new Location(LocationType.STATION, "80129"))));
	}

	@Test
	public void nearbyLocationsShouldLimitMaxResults() throws Exception {
		Location coord = Location.coord(55602712, 13001238);
		EnumSet<LocationType> types = EnumSet.of(LocationType.ANY);
		final NearbyLocationsResult result = queryNearbyLocations(types, coord, 0, 1);
		print(result);

		assertEquals("Should have included only 1 location: " + result.locations,
				1, result.locations != null ? result.locations.size() : 0);
	}

	@Test
	public void queryDepartures() throws Exception {
		Calendar tomorrowAt8 = tomorrowAt8();
		final StationDepartures departures = queryDepartures("81216", tomorrowAt8, 0);

		for(Departure d : departures.departures) {
			assertNotNull(d.destination);
			assertNotNull(d.line);

			Calendar planned = calendar(d.plannedTime);
			Calendar actual = d.predictedTime != null ? calendar(d.predictedTime) : planned;
			String destination = d.destination.name;
			System.out.printf("%02d:%02d[%02d:%02d] @ %s | %s %s | %s%n",
					planned.get(Calendar.HOUR_OF_DAY), planned.get(Calendar.MINUTE),
					actual.get(Calendar.HOUR_OF_DAY), actual.get(Calendar.MINUTE),
					d.position != null ? d.position : '?',
					d.line.product,
					d.line.label,
					destination);
		}
	}

	private static Calendar calendar(Date time) {
		Calendar cal = new GregorianCalendar();
		cal.setTime(time);
		return cal;
	}

	private StationDepartures queryDepartures(String stationId, Calendar when, int max) throws IOException {
		Calendar time = tomorrowAt8();
		QueryDeparturesResult result = provider.queryDepartures(stationId, time.getTime(), max, false);
		print(result);

		assertNotNull(result.stationDepartures);
		assertEquals(1, result.stationDepartures.size());
		StationDepartures departures = result.findStationDepartures(stationId);
		assertNotNull(departures);
		assertNotNull(departures.location);
		assertNotNull(departures.departures);
		assertFalse(departures.departures.isEmpty());

		return departures;
	}

	private static Calendar tomorrowAt8() {
		Calendar time = new GregorianCalendar();
		time.add(DATE, 1);
		time.set(Calendar.HOUR_OF_DAY, 8);
		time.set(Calendar.MINUTE, 0);
		time.set(Calendar.SECOND, 0);
		return time;
	}

	@Test
	public void queryDeparturesInvalidStation() throws Exception {
		final QueryDeparturesResult result = queryDepartures("999999", false);
		assertEquals(QueryDeparturesResult.Status.INVALID_STATION, result.status);
	}

	public void shortTrip() throws Exception {
		final QueryTripsResult result = queryTrips(
				new Location(LocationType.STATION, "740014867", null, "Luleå Airport"), null,
				new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"), new Date(), true, Product.ALL,
				WalkSpeed.NORMAL, Accessibility.NEUTRAL);
		print(result);

		if(!result.context.canQueryLater())
			return;

		final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
		print(laterResult);
	}

	public void shortStockholmTrip() throws Exception {
		final QueryTripsResult result = queryTrips(new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"),
				null, new Location(LocationType.STATION, "740020101", "Stockholm", "Slussen T-bana"), new Date(), true,
				Product.ALL, WalkSpeed.NORMAL, Accessibility.NEUTRAL);
		print(result);

		if(!result.context.canQueryLater())
			return;

		final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
		print(laterResult);
	}

	public void longTrip() throws Exception {
		final QueryTripsResult result = queryTrips(
				new Location(LocationType.STATION, "740098086", 67859847, 20212802, null, "KIRUNA"), null,
				new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"), new Date(), true, Product.ALL,
				WalkSpeed.NORMAL, Accessibility.NEUTRAL);
		print(result);

		if(!result.context.canQueryLater())
			return;

		final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
		print(laterResult);
	}

	private static Location findFirst(LocationType type, List<Location> locations) {
		if(locations != null) {
			for(Location l : locations) {
				if(l.type == type) {
					return l;
				}
			}
		}
		throw new AssertionError("No location found of type " + type + ": " + locations);
	}
}
