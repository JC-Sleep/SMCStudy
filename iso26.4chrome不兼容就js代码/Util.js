/*

 * This class includes a collection of useful functions.

 *

 * When an error occurs during the execution of a method,

 * the exception object is set to store a description of error.

 * The exception object is cleared each time a method is executed.

 *

 * @author  Patrick Li

 * @version 1.0, 05-Apr-2000

 *          1.1, 28-Apr-2000

 *          1.2, 15-Aug-2000

 *          1.3, 13-Nov-2000

 *          2.0, 01-Dec-2001

 *          2.1, 11-Feb-2002

 *          2.2, 11-Sep-2002

 */

function Util() {

    this.exception = null;

    this.locked = false;

}



/*

 * Executes a function if and only if a lock is acquired successfully.

 *

 * @param  f   a string evaluating to the function to execute

 * @param  b   a boolean indicating whether to release the lock after execution

 *             [@optional] [@default false]

 *

 * @return     the result of execution of the function

 * @exception  "already locked"  if a lock cannot be acquired

 *

 * @since  1.0

 */ 

function Util_execute(f, b) {

    this.clearException();



    if (this.locked) {

        this.exception = new Object();

        this.exception.description = "already locked";

        return;

    }



    this.locked = true;

    if (b == null) b = false;

    var result = eval(f);

    if (b) this.locked = false;

    return result;

}



/*

 * Acquire the lock.

 *

 * @since  1.0

 */

function Util_lock() {

    this.clearException();

    this.locked = true;

}



/*

 * Releases the lock.

 *

 * @since  1.0

 */

function Util_unlock() {

    this.clearException();

    this.locked = false;

}



/*

 * Enables an image button.  Sets its appearance to reflect this.

 * It sets the image to the SRC-ENABLED attribute of the image.

 *

 * @param  obj    the image button

 *

 * @since  1.0

 */

function Util_enableImage(obj) {

    this.clearException();

    obj.disabled = false;

    obj.src = obj.getAttribute("SRC-ENABLED");

    obj.style.cursor = "hand";

}



/*

 * Disables an image button.  Sets its appearance to reflect this.

 * It sets the image to the SRC-DISABLED attribute of the image.

 *

 * @param  obj    the image button

 *

 * @since  1.0

 */

function Util_disableImage(obj) {

    this.clearException();

    obj.disabled = true;

    obj.src = obj.getAttribute("SRC-DISABLED");

    obj.style.cursor = "default";

}



/*

 * Enables a control.  Sets its appearance to reflect this.

 * It sets the style to the STYLE-ENABLED attribute of the control.

 *

 * @param  obj    the control

 * @param  revert whether revert to previous status (disabled/readonly)

 *

 * @since  1.2

 */

function Util_enableControl(obj, revert) {

    this.clearException();

    if (revert != true) revert = false;

    if (obj.tagName == "TEXTAREA" || obj.type == "text" || obj.type == "password") {

        if (revert && obj.getAttribute("oldReadOnly") != null) {

            obj.readOnly = obj.getAttribute("oldReadOnly");

        } else {

            obj.readOnly = false;

        }

    } else {

        if (revert && obj.getAttribute("oldDisabled") != null) {

            obj.disabled = obj.getAttribute("oldDisabled");

        } else {

            obj.disabled = false;

        }

    }



    if (obj.getAttribute("STYLE-ENABLED") != null) {

        obj.style.cssText = obj.getAttribute("STYLE-ENABLED");

    }

    if (obj.getAttribute("ONENABLE") != null) {

        eval(obj.getAttribute("ONENABLE"));

    }

}



/*

 * Disables a control.  Sets its appearance to reflect this.

 * It sets the style to the STYLE-DISABLED attribute of the control.

 *

 * @param  obj    the control

 *

 * @since  1.2

 */

function Util_disableControl(obj) {

    this.clearException();

    if (obj.tagName == "TEXTAREA" || obj.type == "text" || obj.type == "password") {

        obj.setAttribute("oldReadOnly", obj.readOnly);

        obj.readOnly = true;

    } else {

        obj.setAttribute("oldDisabled", obj.disabled);

        obj.disabled = true;

    }



    if (obj.getAttribute("STYLE-DISABLED") != null) {

        obj.style.cssText = obj.getAttribute("STYLE-DISABLED");

    }

    if (obj.getAttribute("ONDISABLE") != null) {

        eval(obj.getAttribute("ONDISABLE"));

    }

}



/*

 * Enables an array of control.  Sets their appearances to reflect this.

 * It sets the style to the STYLE-ENABLED attribute of the controls.

 *

 * @param  array    the control array

 * @param  revert   whether revert to previous status (disabled/readonly)

 *

 * @since  1.3

 */

function Util_enableControls(array, revert) {

    if (revert != true) revert = false;

    if (array.length == null) {

        this.enableControl(array, revert);

    } else {

        for (var i = 0; i < array.length; i++) {

            this.enableControl(array[i], revert);

        }

    }

}



/*

 * Disables an array of controls.  Sets their appearances to reflect this.

 * It sets the style to the STYLE-DISABLED attribute of the controls.

 *

 * @param  array    the control array

 *

 * @since  1.3

 */

function Util_disableControls(array) {

    if (array.length == null) {

        this.disableControl(array);

    } else {

        for (var i = 0; i < array.length; i++) {

            this.disableControl(array[i]);

        }

    }

}



/*

 * Enables controls in a document.  Sets their appearances to reflect this.

 * It sets the style to the STYLE-ENABLED attribute of the controls.

 *

 * @param  doc  the document

 *

 * @since  2.2

 */

function Util_enableDocument(doc) {

    this.enableControls(doc.all.tags("INPUT"), true);

    this.enableControls(doc.all.tags("TEXTAREA"), true);

}



/*

 * Disables controls in a document.  Sets their appearances to reflect this.

 * It sets the style to the STYLE-DISABLED attribute of the controls.

 *

 * @param  doc  the document

 *

 * @since  2.2

 */

function Util_disableDocument(doc) {

    this.disableControls(doc.all.tags("INPUT"));

    this.disableControls(doc.all.tags("TEXTAREA"));

}







/*

 * Removes all preceding and trailing whitespaces of a string.

 *

 * @param  string  the string to trim

 * @return the trimmed string

 *

 * @since  1.0

 * @deprecated  use String.trim() instead (need StringUtil.js)

 */

function Util_trim(string) {

    this.clearException();

    if (string.length == 0) return string;

    if (string.charAt(0) == " ") return this.trim(string.substring(1, string.length));

    if (string.charAt(string.length - 1) == " ") return this.trim(string.substring(0, string.length - 1));

    return string;

}



/*

 * Converts key to upper case.

 *

 * @param  keyCode  the key to convert

 * @return the converted key

 *

 * @since  1.1

 */

function Util_keyUpper(keyCode) {

    if (keyCode >= 97 && keyCode <= 122) {

        return keyCode - 32;

    }

    return keyCode;

}



/*

 * Clears the exception.  This function is called automatically

 * every time a method is called.

 *

 * @since  1.0

 */

function Util_clearException() {

    this.exception = null;

}



/*

 * Sorts an array.

 */

function Util_sort(array, comparator) {

    for (var i = 0; i < array.length - 1; i++) {

        for (var j = i + 1; j < array.length; j++) {

			if ((comparator != null && comparator(array[i], array[j]) > 0) ||

                (comparator == null && array[i].compare != null && array[i].compare(array[j]) > 0) ||

                (comparator == null && array[i].compare == null && array[i] > array[j])

            ) {

                var element = array[i];

                array[i] = array[j];

                array[j] = element;

            }

        }

    }

}



/*

 * Returns the current IE version.

 *

 * @return  the major version if IE browser

 *          -1 otherwise

 * @since 2.0

 */ 

function Util_getIEVersion() {

    var ua = window.navigator.userAgent;

    var msie = ua.indexOf("MSIE ")

    

    if (msie > 0) { // is Microsoft Internet Explorer; return version number

        return parseFloat(ua.substring(msie + 5, ua.indexOf(";", msie)))

    } else {

        return -1; // is other browser

    }

}





Util.prototype.execute          = Util_execute;

Util.prototype.lock             = Util_lock;

Util.prototype.unlock           = Util_unlock;

Util.prototype.enableImage      = Util_enableImage;

Util.prototype.disableImage     = Util_disableImage;

Util.prototype.enableControl    = Util_enableControl;

Util.prototype.disableControl   = Util_disableControl;

Util.prototype.enableControls   = Util_enableControls;

Util.prototype.disableControls  = Util_disableControls;

Util.prototype.enableDocument   = Util_enableDocument;

Util.prototype.disableDocument  = Util_disableDocument;

Util.prototype.trim             = Util_trim;

Util.prototype.keyUpper         = Util_keyUpper;

Util.prototype.sort             = Util_sort;

Util.prototype.clearException   = Util_clearException;

Util.prototype.getIEVersion     = Util_getIEVersion;

// -------------------------------------------------------------
// fes-compat auto-loader
// Reason: iOS 26.5 WKWebView tightened JS rules, breaking legacy
//         code on iPad Chrome. Loading compat shim here covers
//         all 424 pages that include Util.js - no per-page changes.
// Date:   2026-05-28
// -------------------------------------------------------------
if (typeof window.FES_COMPAT_LOADED === 'undefined') {
    var _fesCompatScript = document.createElement('script');
    _fesCompatScript.src = '/lib/fes-compat.js';
    _fesCompatScript.async = false; // preserve execution order
    (document.head || document.getElementsByTagName('head')[0] || document.body).appendChild(_fesCompatScript);
}


