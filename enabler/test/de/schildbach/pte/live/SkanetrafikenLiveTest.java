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

import de.schildbach.pte.NetworkProvider.Accessibility;
import de.schildbach.pte.NetworkProvider.WalkSpeed;
import de.schildbach.pte.SeProvider;
import de.schildbach.pte.SkanetrafikenProvider;
import de.schildbach.pte.dto.*;
import org.junit.Test;

import java.util.Date;
import java.util.EnumSet;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * @author Andreas Schildbach
 */
public class SkanetrafikenLiveTest extends AbstractProviderLiveTest {
    public SkanetrafikenLiveTest() {
        super(new SkanetrafikenProvider());
    }

    @Test
    public void nearbyStations() throws Exception {
        final NearbyLocationsResult result = queryNearbyStations(new Location(LocationType.STATION, "7414867"));
        print(result);
    }

    @Test
    public void nearbyStationsByCoordinate() throws Exception {
        final NearbyLocationsResult result = queryNearbyLocations(EnumSet.of(LocationType.STATION),
                Location.coord(6167930, 1323215),
                1000, 0);
        print(result);
    }

    @Test
    public void queryDepartures() throws Exception {
        final QueryDeparturesResult result = queryDepartures("7414867", false);
        print(result);
    }

    @Test
    public void queryDeparturesInvalidStation() throws Exception {
        final QueryDeparturesResult result = queryDepartures("999999", false);
        assertEquals(QueryDeparturesResult.Status.INVALID_STATION, result.status);
    }

    @Test
    public void suggestLocations() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Eriksdalsgatan 3A");
        print(result);
    }

    @Test
    public void suggestLocationsUmlaut() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Luleå");
        print(result);
    }

    @Test
    public void suggestLocationsCoverage() throws Exception {
        final SuggestLocationsResult salzburgResult = suggestLocations("Stockholm");
        print(salzburgResult);
        assertThat(salzburgResult.getLocations(), hasItem(new Location(LocationType.STATION, "740098000")));
    }

    @Test
    public void shortTrip() throws Exception {
        final QueryTripsResult result = queryTrips(
                new Location(LocationType.STATION, "740014867", null, "Luleå Airport"), null,
                new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"), new Date(), true, Product.ALL,
                WalkSpeed.NORMAL, Accessibility.NEUTRAL);
        print(result);

        if (!result.context.canQueryLater())
            return;

        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void shortStockholmTrip() throws Exception {
        final QueryTripsResult result = queryTrips(new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"),
                null, new Location(LocationType.STATION, "740020101", "Stockholm", "Slussen T-bana"), new Date(), true,
                Product.ALL, WalkSpeed.NORMAL, Accessibility.NEUTRAL);
        print(result);

        if (!result.context.canQueryLater())
            return;

        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }

    @Test
    public void longTrip() throws Exception {
        final QueryTripsResult result = queryTrips(
                new Location(LocationType.STATION, "740098086", 67859847, 20212802, null, "KIRUNA"), null,
                new Location(LocationType.STATION, "740098000", null, "STOCKHOLM"), new Date(), true, Product.ALL,
                WalkSpeed.NORMAL, Accessibility.NEUTRAL);
        print(result);

        if (!result.context.canQueryLater())
            return;

        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
    }
}
