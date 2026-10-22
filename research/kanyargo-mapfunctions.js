function initialize_map(MapObj, targetDiv) {
	MapObj.MapView = new ol.View({
		center: ol.proj.fromLonLat([mapLng, mapLat]),
		zoom: mapDefaultZoom
	});
	MapObj.Map = new ol.Map({
		target: targetDiv,
		layers: [
		new ol.layer.Tile({
			source: new ol.source.OSM({
			url: "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"
			})
		})
		],
		view: MapObj.MapView
	});
}

function zoomMapToPointX(mViewObj,lon, lat){
	mViewObj.animate({
          center: new ol.proj.fromLonLat([lon, lat]),
          zoom: 17,
          duration: 350
        });
}

function addMarker(lon,lat,icon) {
	busFeatures.push(new ol.Feature({
        geometry: new ol.geom.Point(ol.proj.transform([parseFloat(lon), parseFloat(lat)], 'EPSG:4326',     
        'EPSG:3857')),
        name: icon,
    }));
}


/*function add_map_point(lat, lng) {
var vectorLayer = new ol.layer.Vector({
source:new ol.source.Vector({
features: [new ol.Feature({
geometry: new ol.geom.Point(ol.proj.transform([parseFloat(lng), parseFloat(lat)], 'EPSG:4326', 'EPSG:3857')),
label: "point",
})]
}),
style: new ol.style.Style({
	image: new ol.style.Icon({
	anchor: [0.5, 0.5],
	anchorXUnits: "fraction",
	anchorYUnits: "fraction",
	src: "https://upload.wikimedia.org/wikipedia/commons/e/ec/RedDot.svg"
	})
})
});
map.addLayer(vectorLayer);
}*/