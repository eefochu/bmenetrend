function setCollapsibleDropDown(){
	var coll = document.getElementsByClassName("collapsible");
	var i;

	for (i = 0; i < coll.length; i++) {
		coll[i].addEventListener("click", function() {
			this.classList.toggle("active");
			var content = this.nextElementSibling;
			if (content.style.display === "block") {
				content.style.display = "none";
			} else {
				content.style.display = "block";
			}
		});
	}
}

function unloadPopupBox(popupBoxId) {    
    $('#'+popupBoxId).fadeOut("slow");
    $("#container").css({       
        "opacity": "1"  
    }); 
    $(".logos").css({       
        "opacity": "1"  
    });
    
}    

function loadPopupBox(popupBoxId) {    
    $('#'+popupBoxId).fadeIn("slow");
    $("#container").css({ 
        "opacity": "0.1"  
    });
    $(".logos").css({       
        "opacity": "0.1"  
    });
    
    /*id = setInterval(function() {
        counter--;
        if(counter < 1) {
            clearInterval(id);

            unloadPopupBox();
        } else {
            $("#countDown").text("Az ablak automatikusan be fog zárulni " + counter.toString() + " másodperc múlva.");
        }
    }, 1000);*/
}

function loadPopupBoxWithTimer(popupBoxId) {    

    var counter = 10;
    var id;
    $('#'+popupBoxId).fadeIn("slow");
    $("#container").css({ 
        "opacity": "0.1"  
    });
    $(".logos").css({       
        "opacity": "0.1"  
    });

    id = setInterval(function() {
        counter--;
        if(counter < 1) {
            clearInterval(id);

            unloadPopupBox(popupBoxId);
        } else {
            $("#countDown").text("Az ablak automatikusan be fog zárulni " + counter.toString() + " másodperc múlva.");
        }
    }, 1000);

}

function setDefaultZoom(){
	document.body.style.zoom = 1.0
	var scale = 'scale(1)';
	// Chrome, Opera, Safari
	document.body.style.webkitTransform =  scale; 
	// IE 9
	document.body.style.msTransform =   scale;
	// General
	document.body.style.transform = scale;     
}

function filterInput(event) {
    var keyCode = ('which' in event) ? event.which : event.keyCode;
    //del, backspace, arrows
    if(keyCode == 8 || keyCode == 37 || keyCode == 38 || keyCode == 39 || keyCode == 40 || keyCode == 46 || keyCode == 9){
    	return true;
    }
    var specNumChars = (keyCode == 69 || keyCode == 111 || keyCode == 107 || keyCode == 109);
    if(!specNumChars){
    	if(document.getSelection().toString() === event.target.value){
			return true;
		}
		var elementVal = event.target.value;
		var maxlength = event.target.maxLength;
		if(elementVal.length>=(maxlength-1)){
			if(maxlength>1){
				event.target.value = elementVal.substring(0, (maxlength-1));
			}
			return maxlength>1;
		}
    }
    return !specNumChars;
};

function handlePaste (e) {
    var clipboardData, pastedData;

    clipboardData = e.clipboardData || window.clipboardData;
    pastedData = clipboardData.getData('Text').toUpperCase();

    if(pastedData.indexOf('E')>-1) {
        e.stopPropagation();
        e.preventDefault();
    }
};

function hideDetails(){
	var det = document.getElementById("purchase-details");
	var clBtn  = document.getElementById("closeDetailsBtn");
	  if (det.style.display === "none") {
		  det.style.display = "block";
		  clBtn.innerHTML = "[Bezár]";
	  } else {
		  det.style.display = "none";
		  clBtn.innerHTML = "[Kinyit]";
	  }
};

function validateEmail(email) {
    var re = /^(([^<>()\[\]\\.,;:\s@"]+(\.[^<>()\[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
    return re.test(String(email).toLowerCase());
};

function scrollToElement(element_id){
	var top = $('#' + element_id).position().top;
	$(window).scrollTop( top );
}

	