<%--
  20241030 Chrome compatibility EvaYao (m1)
--%>
<%@ page import="fes.helper.*, fes.sa.*, fes.sa.settle.*, java.text.*, java.util.*" contentType="text/html"%>
<jsp:useBean id="settleBean" class="fes.sa.settle.SASettleBean" scope="session" />
<%
Locale locale = (Locale) session.getAttribute("locale");
ResourceBundle labelBundle   = ResourceBundle.getBundle("fes.sa.settle.settle_label", locale);
ResourceBundle messageBundle = ResourceBundle.getBundle("fes.sa.settle.settle_message", locale);
ResourceBundle imageBundle   = ResourceBundle.getBundle("fes.sa.settle.settle_image", locale);
//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
String location = (String)session.getAttribute("POS.location");
boolean allow_a8_integration = PosA8PaymentHelper.isAllowA8Integration(location);
System.out.println("SASettlePage.jsp allow_a8_integration="+allow_a8_integration);
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
//Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2 (SR0030420)
boolean allow_point_dollars = SHKPRebateHelper.isAllowPointDollars(location);
System.out.println("SASettlePage.jsp allow_point_dollars="+allow_point_dollars);
//End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2 (SR0030420)
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<HTML>
<HEAD>
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=<%=session.getAttribute("charset")%>">
<TITLE><%=labelBundle.getString("TitleSettlement")%></TITLE>
<LINK REL="stylesheet" HREF="/jsp/pos5.css" type="text/css">
<SCRIPT SRC="/lib/Util.js"></SCRIPT>
<SCRIPT SRC="/lib/StringUtil.js"></SCRIPT>
<SCRIPT SRC="/lib/KeyHandler.js"></SCRIPT>
<SCRIPT SRC="/lib/ImageHandler.js"></SCRIPT>
<SCRIPT SRC="/lib/Dictionary.js"></SCRIPT>
<SCRIPT SRC="/lib/Array.js"></SCRIPT>
<%-- Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2(SR0030420) --%>
<script type="text/javascript" src="/lib/dojo/dojo.js"></script>
<%-- End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2 (SR0030420) --%>
<SCRIPT SRC="/jsp/fes/sa/settle/SASettleScript.js"></SCRIPT>
<SCRIPT LANGUAGE="JavaScript" SRC="/jsp/Script5.jsp"></SCRIPT>
<script src="/lib/jquery-1.11.3.min.js"></script>
<script src="/lib/jquery-plugins/jquery-ui-1.9.2/jquery-ui-1.9.2.custom.min.js"></script>
<%-- Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2(SR0030420) --%>
<SCRIPT SRC="/jsp/fes/sa/settle/SASettleCommon.js"></SCRIPT>
<%-- End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone Phrase 2 (SR0030420) --%>
<SCRIPT LANGUAGE="vbscript">
    Function FormatCurrVB(Value)
        // Modified by William Tam on 6 Jul 2015, for bug fixing
        //FormatCurr = FormatNumber(Value, 2, , , false)
        FormatCurrVB = FormatNumber(Number(Value), 2, , , false)
        // End of Modified by William Tam on 6 Jul 2015, for bug fixing
    End Function
</SCRIPT>
<SCRIPT><!--
var util = new Util();
var specialCustomer = new Array("66666666", "88888888");

var customers = new Dictionary();
var invoices = new Dictionary();
var payments = new Dictionary();
//var garbages = new Dictionary();
// Added by William Tam on 24 Jun 2009, for BM Security
var excludeInvoiceGroups = new Dictionary();
// End of added by William Tam on 24 Jun 2009, for BM Security
var pool = new Dictionary();
var jobQueue = new Dictionary();
var nextJobId = 0;
var saveStatus = "idle";

//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
var allowA8Payment = ("<%=allow_a8_integration%>" == "true");
var rtnA8AuthCode = new Array();
var rtnA8PayCode = new Array();
var rtnA8CashDollar = new Array();
var rtnA8MaskedCardNo = new Array();
var rtnA8BatchNo = "";
var verifiedOk = false; // m1
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
//Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone  phrase 2 (SR0030420)        
var allowPointDollars = ("<%=allow_point_dollars%>" == "true");
var rtnShkpPayCode = "";
var rtnShkpTransactionId = "";
var rtnShkpBatchNo = "";
var devicePayResult = false;
var hasThePointPayment = false;
//End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone phrase 2 (SR0030420)
// m1 - Begin
var childWins = [];
var isCallingSearchHKID;
var searchHKIDResult;
var searchInterval = null;
var alertWin;
var alertInterval = true;
var doPaymentInterval = null;
var a8PaymentInterval = null;

function FormatCurrJS(Value) {
    var v = null;
    if (typeof Value == 'string') {
        v = Number(Value);
    } else {
        if (Value==null) {
            v = 0;
        }
        else { 
            v = Value;
        }
    }

    return v.toFixed(2);
}

function FormatCurr(Value){
    if(window.ActiveXObject){
        return FormatCurrVB(Value);
    }else{
        return FormatCurrJS(Value);
    }
}
// m1 - End
        
// Send a request to the server asynchronously.
function sendRequest(row, url) {
    var jobId = nextJobId++;
    if (url.indexOf("?") == -1) {
        url += "?jobId=" + jobId;
    } else {
        url += "&jobId=" + jobId;
    }

    jobQueue.add(jobId, row);
    var html = "<IFRAME NAME=f" + jobId + " SRC=\"" + url + "\" ONREADYSTATECHANGE=\"checkRequestState(" + jobId + ")\" FRAMEBORDER=0></IFRAME>";
    document.all.divAction.insertAdjacentHTML('beforeEnd', html);
}

// Receives notification when a server request is complete
function requestComplete(jobId) {
    jobQueue.remove(jobId);
}

// Check request state
function checkRequestState(jobId) {
    var doc = eval("top.f" + jobId + ".document");
    if (doc.readyState != "complete") return;
    if (jobQueue.exists(jobId)) {
        searchError(jobId, "<%=messageBundle.getString("ServerError")%>");
        requestComplete(jobId);
//    } else {
//        document.all["f" + jobId].outerHTML = "";
    }
}

// Alert message if first time, restore old value otherwise
function rerouteAlert(field, message) {
    if (field.getAttribute("invalidValue") != field.value) {
        field.setAttribute("invalidValue", field.value);
        alert(message);
        if(window.showModalDialog){ // m1
            field.focus();
        }else{  // m1 - Begin
            setTimeout(function(){
                field.focus();
            }, 500);
        }   // m1 - End
        return false;
    } else {
        field.removeAttribute("invalidValue");
        if (field.getAttribute("oldValue") != null) {
            field.value = field.getAttribute("oldValue");
        } else {
            field.value = "";
        }
        return true;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Receives notification when a payment code changes
function changePaymentCode(field) {
    if (field.value == field.getAttribute("oldValue")) return true;
    var row = field.parentElement.parentElement;
/*
    var customerNumber = tblOutstanding.rows(row.rowIndex).cells(1).children(0).value;
    var payment;
    if (specialCustomer.indexOf(customerNumber) < 0) { // Customer record
        payment = payments.item(customerNumber);
    } else { // Invoice record
        var invoiceNumber = tblOutstanding.rows(row.rowIndex).cells(3).children(0).value;
        payment = payments.item(invoiceNumber);
    }
*/
    // m1 - Begin
    var payment;
    var tblOutstanding = document.getElementById("tblOutstanding");
    if(window.showModalDialog){
    /*var payment = payments.item(tblOutstanding.rows(row.rowIndex).cells(0).children(0).value);*/
        payment = payments.item(tblOutstanding.rows(row.rowIndex).cells(0).children(0).value);
    }else{
        payment = payments.item(tblOutstanding.rows[row.rowIndex].cells[0].children[0].value);
    }
    // m1 - End

    switch (field.value) {
        case "":
        case " ":
            payment.method[0] = null;
            payment.method.length = 0;
            field.setAttribute("oldValue", field.value);
            return true;
        case "A":
        //Added by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
        case "B":
        //End Added by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
        case "C":
        //case "D":
        case "E":
        case "M":
        case "P":
        case "Q":
        case "V":
        // Added by William Tam on 23 May 2011, for new payment code PAYWARE
        //Modified by Jackson Luo on 2024-03-20 for Remove OCTOR and other payment type which are no longer use in FES BIS (SCR0033435 & SCR202403200013)
        //case "W":
        //End Modified by Jackson Luo on 2024-03-20 for Remove OCTOR and other payment type which are no longer use in FES BIS (SCR0033435 & SCR202403200013)
        // End of added by William Tam on 23 May 2011, for new payment code PAYWARE
        // Added by Wilson Ng on 27-08-2013 for new payment code MPOS (201308220014)
        case "O":
        // End aded by Wilson Ng on 27-08-2013 for new payment code MPOS (201308220014)
        // Added by Wilson Ng on 07-03-2012 for adding new payment code CUP
        case "U":
        // End added by Wilson Ng on 07-03-2012 for adding new payment code CUP
        // Added by Wilson Ng on 10-04-2012 for adding new payment code RMB-CARD
        //Modified by Jackson Luo on 2024-03-20 for Remove OCTOR and other payment type which are no longer use in FES BIS (SCR0033435 & SCR202403200013)
        //case "R":
        //End Modified by Jackson Luo on 2024-03-20 for Remove OCTOR and other payment type which are no longer use in FES BIS (SCR0033435 & SCR202403200013)
        // End added by Wilson Ng on 10-04-2012 for adding new payment code RMB-CARD
        // Added by Wilson Ng on 08-09-2014 for new payment code GPM4230 (201409020037)
        case "G":
        // End added by Wilson Ng on 08-09-2014 for new payment code GPM4230 (201409020037)
        // Added by Wilson Ng on 20-04-2015 for adding new payment codes HSBVMCUP, N-HSBVM, N-HSBCUP (201503300025)
        case "H":
        case "N":
        case "S":
        // End added by Wilson Ng on 20-04-2015 for adding new payment codes HSBVMCUP, N-HSBVM, N-HSBCUP (201503300025) 
        // Commented by Billy Pang on 10/06/2021 to align production
        // // Added by Wilson Ng on 24-08-2015 for adding new payment codes BHS12, BHS24, B-HSBVM, BN-HSBVM (201508010001)
        // case "B":
        // case "F":
        // // End added by Wilson Ng on 24-08-2015 for adding new payment codes BHS12, BHS24, B-HSBVM, BN-HSBVM (201508010001)
        // End Commented by Billy Pang on 10/06/2021 to align production
        // Added by Billy Pang on 27/07/2017 for adding new payment code HSBCD (201707260038)
        case "D":
        // End Added by Billy Pang on 27/07/2017 for adding new payment code HSBCD (201707260038)
        // Added by Billy pang on 2/11/2018 for adding new payment code TTREMIT , ATMTRF
        case "T":
        case "L":
        // End Added by Billy pang on 2/11/2018 for adding new payment code TTREMIT , ATMTRF
        //Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone phrase 2 (SR0030420)
        case "K":
        //End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone phrase 2 (SR0030420)      
        //Added by Kent Li on 03/08/2022 for BoC Pay (SR0033829)
        case "Y":
        //End Added by Kent Li on 03/08/2022 for BoC Pay (SR0033829)
        // Added by Billy Pang on 10/06/2021 for adding new payment code OCTOC (SR0024972)
        case "Z":
        // End Added by Billy Pang on 10/06/2021 for adding new payment code OCTOC (SR0024972)
            payment.method[0] = new PaymentMethod();
            payment.method[0].code = field.value;
            payment.method[0].paymentAmount = payment.paymentAmount;
            payment.method.length = 1;

            if(window.showModalDialog){ // m1
            field.parentElement.children(1).className = "textbox";
            field.parentElement.children(1).readOnly = false;
            }else{  // m1 - Begin
                field.parentElement.children[1].className = "textbox";
                field.parentElement.children[1].readOnly = false;
            }   // m1 - End
            field.setAttribute("oldValue", field.value);
            return true;
        //Commented by Kent Li on 03/08/2022 for BoC Pay (SR0033829)
        //Added by Devin Chen on 18/07/2022 for BoC Pay (SR0033829)
        //case "Y":
        //End Added by Devin Chen on 18/07/2022 for BoC Pay (SR0033829)
        //End Commented by Kent Li on 03/08/2022 for BoC Pay (SR0033829)
        default:
            //alert("<%=messageBundle.getString("InvalidPaymentCode")%>");
            //field.focus();
            //return false;
            return rerouteAlert(field, "<%=messageBundle.getString("InvalidPaymentCode")%>");
    }
}

// Receives notification when a payment amount changes
function changePaymentAmount(field) {
    if (field.value == field.getAttribute("oldValue")) return true;
    var value = convertAmount(field);
    if (value == null) {
        //alert("<%=messageBundle.getString("InvalidPaymentAmount")%>");
        //field.focus();
        return rerouteAlert(field, "<%=messageBundle.getString("InvalidPaymentAmount")%>");
    }

    var tblOutstanding = document.all.tblOutstanding;
    var row = field.parentElement.parentElement;
    //var customerNumber = tblOutstanding.rows(row.rowIndex).cells(1).children(0).value;
    // m1 - Begin
    var key;
    /*var key = tblOutstanding.rows(row.rowIndex).cells(0).children(0).value;*/
    if(window.showModalDialog){
        key = tblOutstanding.rows(row.rowIndex).cells(0).children(0).value;
    }else{
        key = tblOutstanding.rows[row.rowIndex].cells[0].children[0].value;
    }
    // m1 - End
    var customer = null;
    var payment;

    //if (specialCustomer.indexOf(customerNumber) < 0) {
    if (customers.exists(key)) {
        customer = customers.item(key);
        payment = payments.item(key);
        customer.paymentAmount = value;
    } else { // Invoice record
        //var invoiceNumber = tblOutstanding.rows(row.rowIndex).cells(3).children(0).value;
        var invoice = invoices.item(key);

        if (value < invoice.chargeAmount) {
            //alert("<%=messageBundle.getString("PaymentLessThanCharge")%>");
            //field.focus();
            return rerouteAlert(field, "<%=messageBundle.getString("PaymentLessThanCharge")%>");
        }

        if (value > invoice.outstandingAmount) {
            //alert("<%=messageBundle.getString("PaymentExceedOs")%>");
            //field.focus();
            return rerouteAlert(field, "<%=messageBundle.getString("PaymentExceedOs")%>");
        }

        payment = payments.item(key);
        invoice.paymentAmount = value;
        invoice.settleAmount = value;
        payment.settleAmount = value;
        // Modified by William Tam on 24 Jun 2009, for BM Security
        //row.cells(8).children(0).value = FormatCurr(invoice.settleAmount);
        // m1 - Begin
        /*row.cells(row.cells.length-1).children(0).value = FormatCurr(invoice.settleAmount);*/
        if(window.showModalDialog){
            row.cells(row.cells.length-1).children(0).value = FormatCurr(invoice.settleAmount);
        }else{
            row.cells[row.cells.length-1].children[0].value = FormatCurr(invoice.settleAmount);
        }
        // m1 - End
        // End of modified by William Tam on 24 Jun 2009, for BM Security
    }

    payment.paymentAmount = value;
    if (payment.method[0] != null) {
        payment.method[0].paymentAmount = value;
    }
/*
    //if (customer != null) {
        // Update subtotal and total amount first......
        customer.settleAmount = Math.min(customer.outstandingAmount, customer.paymentAmount);
        payments.item(customer.customerNumber).settleAmount = customer.settleAmount;
        row.cells(7).children(0).value = FormatCurr(customer.settleAmount);
        document.all.settleTotal.value = FormatCurr(
            new Number(document.all.settleTotal.value) -
            new Number(row.cells(7).children(0).getAttribute("oldValue")) +
            customer.settleAmount
        );
        row.cells(7).children(0).setAttribute("oldValue", customer.settleAmount);

        // .... then auto allocate.
        autoAllocate(row, customer);
    }
*/
    payment.askAdvancedPayment = (payment.paymentAmount > payment.settleAmount);
    field.setAttribute("oldValue", field.value);
    return true;
}

// Receives notification when a settle amount changes
function changeSettleAmount(field) {
    if (field.value == field.getAttribute("oldValue")) return true;
    var value = convertAmount(field);
    if (value == null) {
        //alert("<%=messageBundle.getString("InvalidSettleAmount")%>");
        //field.focus();
        //return false;
        return rerouteAlert(field, "<%=messageBundle.getString("InvalidSettleAmount")%>");
    }

    var tblOutstanding = document.all.tblOutstanding;
    var row = field.parentElement.parentElement;

    // Validate
    // m1 - Begin
    var tmpEle;
    if(window.showModalDialog){
        tmpEle = row.cells(7).children(0);
    }else{
        tmpEle = row.cells[7].children[0];
    }
    /*if (value > new Number(row.cells(7).children(0).value)) {*/
    if (value > new Number(tmpEle.value)) {
    // m1 - End
        //alert("<%=messageBundle.getString("SettleExceedOs")%>");
        //field.focus();
        //return false;
        return rerouteAlert(field, "<%=messageBundle.getString("SettleExceedOs")%>");
    }

    // Find the subtotal field
    var i = 1;
    // Modified by William Tam on 24 Jun 2009, for BM Security
    //while (tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).value == "") {
    if(window.showModalDialog){ // m1
    while (tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).value == "" || 
           tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).type != "text" ) {
    // End of Modified by William Tam on 24 Jun 2009, for BM Security
        i++;
    }
    }else{  // m1 - Begin
        while (tblOutstanding.rows[row.rowIndex - i].cells[1].children[0].value == "" || 
               tblOutstanding.rows[row.rowIndex - i].cells[1].children[0].type != "text" ) {
            i++;
        }
    }   // m1 - End

    // Refresh the subtotal field
    // m1 - Begin
    /*var customerNumber = tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).value;*/
    var customerNumber;
    if(window.showModalDialog){
        customerNumber = tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).value;
    }else{
        customerNumber = tblOutstanding.rows[row.rowIndex - i].cells[1].children[0].value;
    }
    // m1 - End
    var customer = customers.item(customerNumber);
    var payment = payments.item(customerNumber);

    // m1 - Begin
    if(window.showModalDialog){
        tmpEle = tblOutstanding.rows(row.rowIndex - i).cells(7).children(0);
    }else{
        tmpEle = tblOutstanding.rows[row.rowIndex - i].cells[7].children[0];
    }
    // m1 - End
    customer.invoices[i - 1].settleAmount = value;
    // m1 - Begin
    /*
    customer.settleAmount =
        new Number(tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).value) -
        new Number(field.getAttribute("oldValue")) + value;
    */
    customer.settleAmount = new Number(tmpEle.value) - new Number(field.getAttribute("oldValue")) + value;
    // m1 - End
    payment.settleAmount = customer.settleAmount;
    // m1 - Begin
    /*
    tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).value = FormatCurr(customer.settleAmount);
    tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).setAttribute("oldValue", FormatCurr(customer.settleAmount));
    */
    tmpEle.value = FormatCurr(customer.settleAmount);
    tmpEle.setAttribute("oldValue", FormatCurr(customer.settleAmount));
    // m1 - End

    // Refresh the total field
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) -
        new Number(field.getAttribute("oldValue")) + value
    );

    payment.askAdvancedPayment = (payment.paymentAmount > payment.settleAmount);
    field.setAttribute("oldValue", field.value);
    return true;
}

// Receives notification when a settle amount changes
function changeSettleSubtotal(field) {
    if (field.value == field.getAttribute("oldValue")) return true;
    var value = convertAmount(field);
    if (value == null) {
        //alert("<%=messageBundle.getString("InvalidSettleAmount")%>");
        //field.focus();
        //return false;
        return rerouteAlert(field, "<%=messageBundle.getString("InvalidSettleAmount")%>");
    }

    // Validate
    var row = field.parentElement.parentElement;
    if(window.showModalDialog){ // m1
    if (row.cells(6).children(1) != null) {
        var creditAmount = row.cells(6).children(1).value;
        creditAmount = new Number(creditAmount.substring(1, creditAmount.length - 1));
        if (value > new Number(row.cells(6).children(0).value) + creditAmount) {
            //alert("<%=messageBundle.getString("SettleExceedOsCredit")%>");
            //field.focus();
            //return false;
            return rerouteAlert(field, "<%=messageBundle.getString("SettleExceedOsCredit")%>");
        }
    } else {
        if (value > new Number(row.cells(6).children(0).value)) {
            //alert("<%=messageBundle.getString("SettleExceedOs")%>");
            //field.focus();
            //return false;
            return rerouteAlert(field, "<%=messageBundle.getString("SettleExceedOs")%>");
        }
    }
    }else{  // m1 - Begin
        if (row.cells[6].children[1] != null) {
            var creditAmount = row.cells[6].children[1].value;
            creditAmount = new Number(creditAmount.substring(1, creditAmount.length - 1));
            if (value > new Number(row.cells[6].children[0].value) + creditAmount) {
                return rerouteAlert(field, "<%=messageBundle.getString("SettleExceedOsCredit")%>");
            }
        } else {
            if (value > new Number(row.cells[6].children[0].value)) {
                return rerouteAlert(field, "<%=messageBundle.getString("SettleExceedOs")%>");
            }
        }
    }   // m1 - End

    // Refresh the subtotal field
    // m1 - Begin
    /*
    var customerNumber = tblOutstanding.rows(row.rowIndex).cells(1).children(0).value;
    */
    var customerNumber = "";
    if(window.showModalDialog){
        customerNumber = tblOutstanding.rows(row.rowIndex).cells(1).children(0).value;
    }else{
        customerNumber = tblOutstanding.rows[row.rowIndex].cells[1].children[0].value;
    }
    // m1 - End
    var customer = customers.item(customerNumber);
    var payment = payments.item(customerNumber);

    // Added by William Tam on 24 Jun 2009, for BM Security
   // Modified by Ken Cheng on 18 Aug 2016, for finance processing time
    <% if(settleBean.getUser()!=null){ %>
        if(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%> && customer.startRequest == <%=SASettleConstant.SEARCH_CUST_ADDED%>){     
            var handsetInvoiceTotal = 0;
            for(var i=0; i < customer.invoices.length; i++){
                if(customer.invoices[i].invoiceType == "O" || customer.invoices[i].invoiceType == "W")
                    handsetInvoiceTotal = new Number(handsetInvoiceTotal) + new Number(customer.invoices[i].settleAmount);
            }
            if( handsetInvoiceTotal > value && handsetInvoiceTotal > 0){ 
                return rerouteAlert(field, "<%=messageBundle.getString("InvalidSettleAmount")%>");
            }
        }
    <% } %>
    // End of added by William Tam on 24 Jun 2009, for BM Security
   // End of modified by Ken Cheng on 18 Aug 2016, for finance processing time
    customer.settleAmount = value;
    payment.settleAmount = value;

    // Refresh the total field
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) -
        new Number(field.getAttribute("oldValue")) + value
    );

    // Reallocate settle amount
    autoAllocate(row, customer, customer.expanded);
    payment.askAdvancedPayment = (payment.paymentAmount > payment.settleAmount);
    field.setAttribute("oldValue", field.value);
    return true;
}

function changeChargeAmount(field) {
    if (field.value == field.getAttribute("oldValue")) return true;
    var value = convertAmount(field);
    if (value == null) {
        //alert("<%=messageBundle.getString("InvalidChargeAmount")%>");
        //field.focus();
        //return false;
        return rerouteAlert(field, "<%=messageBundle.getString("InvalidChargeAmount")%>");
    }

    var tblOutstanding = document.all.tblOutstanding;
    var row = field.parentElement.parentElement;

    // Find the subtotal field
    var i = 1;
    if(window.showModalDialog){ // m1
    while (tblOutstanding.rows(row.rowIndex - i).cells(1).children(0).value == "") {
        i++;
    }
    }else{  // m1 - Begin
        while (tblOutstanding.rows[row.rowIndex - i].cells[1].children[0].value == "") {
            i++;
        }
    }   // m1 - End

    // Refresh the subtotal field
    // m1 - Begin
    /*var key = tblOutstanding.rows(row.rowIndex - i).cells(0).children(0).value;*/
    var key;
    if(window.showModalDialog){
        key = tblOutstanding.rows(row.rowIndex - i).cells(0).children(0).value;
    }else{
        key = tblOutstanding.rows[row.rowIndex - i].cells[0].children[0].value;
    }
    // m1 - End
    var invoice = invoices.item(key);
    var payment = payments.item(key);

    var syncPaymentAmount = payment.method.length < 2 && payment.outstandingAmount == payment.paymentAmount;
    invoice.charges[i - 1].chargeAmount = value;
    invoice.chargeAmount += -new Number(field.getAttribute("oldValue")) + value;
    // m1 - Begin
    var tmpEle;
    /*invoice.outstandingAmount =
        new Number(tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).getAttribute("oldValue")) -
        new Number(field.getAttribute("oldValue")) + value;
    */
    if(window.showModalDialog){
        tmpEle = tblOutstanding.rows(row.rowIndex - i).cells(7).children(0);
    }else{
        tmpEle = tblOutstanding.rows[row.rowIndex - i].cells[7].children[0];
    }
    invoice.outstandingAmount = new Number(tmpEle.getAttribute("oldValue")) - new Number(field.getAttribute("oldValue")) + value;
    // m1 - End
    payment.outstandingAmount = invoice.outstandingAmount;
    // m1 - Begin
    /*
    tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).value = FormatCurr(invoice.outstandingAmount);
    tblOutstanding.rows(row.rowIndex - i).cells(7).children(0).setAttribute("oldValue", FormatCurr(invoice.outstandingAmount));
    */
    tmpEle.value = FormatCurr(invoice.outstandingAmount);
    tmpEle.setAttribute("oldValue", FormatCurr(invoice.outstandingAmount));
    // m1 - End

    // Refresh payment amount if appropriate
    if (syncPaymentAmount) {
        // m1 - Begin
        if(window.showModalDialog){
            tmpEle = tblOutstanding.rows(row.rowIndex - i).cells(6).children(1);
        }else{
            tmpEle = tblOutstanding.rows[row.rowIndex - i].cells[6].children[1];
        }
        /*
        tblOutstanding.rows(row.rowIndex - i).cells(6).children(1).value = FormatCurr(
            new Number(tblOutstanding.rows(row.rowIndex - i).cells(6).children(1).getAttribute("oldValue")) -
            new Number(field.getAttribute("oldValue")) + value
        );
        changePaymentAmount(tblOutstanding.rows(row.rowIndex - i).cells(6).children(1));
        */
        tmpEle.value = FormatCurr(new Number(tmpEle.getAttribute("oldValue")) - new Number(field.getAttribute("oldValue")) + value);
        changePaymentAmount(tmpEle);
        // m1 - End
    }

    // Refresh the total field
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) -
        new Number(field.getAttribute("oldValue")) + value
    );

    field.setAttribute("oldValue", field.value);
    return true;
}

// Convert field value to amount
function convertAmount(field) {
    var value = new Number(field.value);
    if (isNaN(value) || value < 0) {
        return null;
    }

    field.value = FormatCurr(value);
    return value;
}

// Auto allocate
function autoAllocate(row, customer, refresh) {
    // No need to allocate if invoices not yet loaded
    if (customer.invoices.length == 0) return false;

    // Reset......
    for (var i = 0; i < customer.invoices.length; i++) {
        // Added by William Tam on 24 Jun 2009, for BM Security
        if(customer.invoices[i].invoiceType != "W" && customer.invoices[i].invoiceType != "O") { 
        customer.invoices[i].settleAmount = 0;
        }
        // End of Added by William Tam on 24 Jun 2009, for BM Security
    }

    // Allocate......
    var remainAmount = customer.settleAmount;
/*Modified by Sam Yung on 17 May for revising Cashier Settlement*/
//    var sequence = new Array("A", "J", "P", "R", "B", "U", "T", "M");
    var sequence = new Array("A", "J", "B", "P", "R", "U", "T", "M", "C");
/*End Modified by Sam Yung on 17 May for revising Cashier Settlement*/
    var sequenceIndex = 0, invoiceIndex = 0;

// Modified by William Tam on 30 Jul 2009, for BM Security
// Modified by Ken Cheng on 18 Aug 2016, for finance processing time
<% if(settleBean.getUser()!=null){ %>
    if(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%> && customer.startRequest == <%=SASettleConstant.SEARCH_CUST_ADDED%>){     
        // use different sequence - by invoice type first
        typeSequence = new Array("O", "W", "D");
        while (remainAmount > 0 && sequenceIndex < typeSequence.length) {
            if (customer.invoices[invoiceIndex].invoiceType == typeSequence[sequenceIndex]) {
                switch(typeSequence[sequenceIndex]){
                    case "O":
                    case "W":
                        if(customer.invoices[invoiceIndex].addedInvoices.length == 0) break;
                        remainAmount -= customer.invoices[invoiceIndex].settleAmount;
                        break;
                    case "D":
                        var allocAmount = Math.min(customer.invoices[invoiceIndex].outstandingAmount, remainAmount);
                        customer.invoices[invoiceIndex].settleAmount = allocAmount;
                        remainAmount -= allocAmount;
                }
            }
            invoiceIndex++;
            if (invoiceIndex == customer.invoices.length) {
                invoiceIndex = 0;
                sequenceIndex++;
            }
        }
        // then by special system indicator
        sequenceIndex = 0;
        invoiceIndex = 0;
        sequence = new Array("J","U","M","C");
        while (remainAmount > 0 && sequenceIndex < sequence.length) {
            if (customer.invoices[invoiceIndex].systemInd == sequence[sequenceIndex]) {
                var allocAmount = Math.min(customer.invoices[invoiceIndex].outstandingAmount, remainAmount);
                customer.invoices[invoiceIndex].settleAmount = allocAmount;
                remainAmount -= allocAmount;
            }
            invoiceIndex++;
            if (invoiceIndex == customer.invoices.length) {
                invoiceIndex = 0;
                sequenceIndex++;
            }
        }

    } else {
// End of modified by Ken Cheng on 18 Aug 2016, for finance processing time
// End of Modified by William Tam on 30 Jul 2009, for BM Security
    while (remainAmount > 0 && sequenceIndex < sequence.length) {
        // Modified by William Tam on 24 Jun 2009, for BM Security
        //if (customer.invoices[invoiceIndex].systemInd == sequence[sequenceIndex]) {
        if (customer.invoices[invoiceIndex].systemInd == sequence[sequenceIndex] &&
            customer.invoices[invoiceIndex].invoiceType != 'E'
        ) {
        // End of Modified by William Tam on 24 Jun 2009, for BM Security
            var allocAmount = Math.min(customer.invoices[invoiceIndex].outstandingAmount, remainAmount);
            customer.invoices[invoiceIndex].settleAmount = allocAmount;
            remainAmount -= allocAmount;
        }

        invoiceIndex++;
        if (invoiceIndex == customer.invoices.length) {
            invoiceIndex = 0;
            sequenceIndex++;
        }
    }

// Modified by William Tam on 30 Jul 2009, for BM Security
    }
<% } %>
// End of Modified by William Tam on 30 Jul 2009, for BM Security

    // Refresh view......
    if (refresh) {
        var tmpEle; // m1
        var array = pool.item(customer.customerNumber);
        var length = customer.invoices.length;
        if (array != null) {
            length = array.index;
        }
        for (var i = 0; i < length; i++) {
            // m1 - Begin
            /*var currentRow = document.all.tblOutstanding.rows(row.rowIndex + i + 1);*/
            var currentRow;
            if(window.showModalDialog){
                currentRow = document.all.tblOutstanding.rows(row.rowIndex + i + 1);
            }else{
                currentRow = document.all.tblOutstanding.rows[row.rowIndex + i + 1];
            }
            // m1 - End
            // Modified by William Tam on 24 Jun 2009, for BM Security
            /*
            currentRow.cells(8).children(0).value = FormatCurr(customer.invoices[i].settleAmount);
            currentRow.cells(8).children(0).setAttribute("oldValue", currentRow.cells(8).children(0).value);
            */
            if(customer.invoices[i].invoiceType != "E"){
                // m1 - Begin
                if(window.showModalDialog){
                    tmpEle = currentRow.cells(currentRow.cells.length-1).children(0);
                }else{
                    tmpEle = currentRow.cells[currentRow.cells.length-1].children[0];
                }
                /*
                currentRow.cells(currentRow.cells.length-1).children(0).value = FormatCurr(customer.invoices[i].settleAmount);
                currentRow.cells(currentRow.cells.length-1).children(0).setAttribute("oldValue", currentRow.cells(currentRow.cells.length-1).children(0).value);
                */
                tmpEle.value = FormatCurr(customer.invoices[i].settleAmount);
                tmpEle.setAttribute("oldValue", tmpEle.value);
                // m1 - End
            }
            // End of Modified by William Tam on 24 Jun 2009, for BM Security
        }
    }
}






///////////////////////////////////////////////////////////////////////////////
// Search a record
function search(process, handleUser, searchNumber) {
    //var customerNumber = frmSearch.customerNumber.value.trim();
    //var subscriberNumber = frmSearch.subscriberNumber.value.trim();
    //var invoiceNumber = frmSearch.invoiceNumber.value.trim();
    var handleUser = handleUser.trim();
    var searchNumber = searchNumber.trim();
    var searchCustNo =frmSearch.searchCustNo.value.trim();

    if (handleUser == "") {
        alert("<%=messageBundle.getString("MissingHandleUser")%>");
        frmSearch.handleUser.focus();
        return false;
    }
    
    if (searchNumber == "" &&  searchCustNo == ""){
        alert("<%=messageBundle.getString("inputSearch")%>");
        frmSearch.searchNumber.focus();
        return false;
    }
   /*
    if (searchNumber == "") {
        if (process == "issueInvoice") {
            searchNumber = "00000000";
        } 
         else {
            alert("<%=messageBundle.getString("MissingSearchNumber")%>");
            frmSearch.searchNumber.focus();
            return false;
        }
    }*/

    var customerNumber = "", subscriberNumber = "", invoiceNumber = "";
    if (searchNumber.charAt(0) == "I" || searchNumber.indexOf("RI") == 0) {
        if (process == "issueInvoice") {
            alert("Invalid input for issue invoice.");
            frmSearch.searchNumber.focus();
            return false;
        } else {
            invoiceNumber = searchNumber;
        }
    } else {
        subscriberNumber = searchNumber;
    }
    customerNumber = searchCustNo;

    if ((customerNumber.length != 0 && customerNumber.length != 8) ||
               (process != "issueInvoice" && customerNumber == "00000000") || 
               customerNumber == "66666666" || customerNumber == "77777777" ||
               customerNumber == "88888888" || customerNumber == "99999999") {
        alert("<%=messageBundle.getString("InvalidCustomerNumber")%>");
        frmSearch.searchNumber.focus();
        return false;
    } else if ((subscriberNumber.length != 0 && subscriberNumber.length != 8 && subscriberNumber.length != 9 && subscriberNumber.length != 10 && subscriberNumber.length != 11) ||
               subscriberNumber == "00000000" || 
               subscriberNumber == "66666666" || subscriberNumber == "77777777" ||
               subscriberNumber == "88888888" || subscriberNumber == "99999999") {
        alert("<%=messageBundle.getString("InvalidSubscriberNumber")%>");
        frmSearch.searchNumber.focus();
        return false;
    }

    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    // m1 - Begin
    /*
    var searchHKIDFlow = false;
    var btnSearch = document.getElementById('btnSearch');  
    var btnSave = document.getElementById('btnSave');  
    var custNumOutput = "";
    var custNumOutputArray = "";
    var custNumSearchedHKIDFlow = "";
    var inputIndex = 0;
    var loopTimeCount = 5;
    var timeCount = 3000;
    */
    
    /*var searchByHKIDResult = searchByHKID(customerNumber , subscriberNumber , false);*/
    var searchByHKIDResult;
    isCallingSearchHKID = true;
    searchHKIDResult = null;
    searchByHKID(customerNumber , subscriberNumber , false);
    searchInterval = setInterval(function(){
        if(!isCallingSearchHKID && searchHKIDResult != null){
            searchByHKIDResult = searchHKIDResult;
            afterSearchByHKID(customerNumber, subscriberNumber, invoiceNumber, process, handleUser, searchByHKIDResult)
            clearInterval(searchInterval);
        }
    }, 100);
    
    /*
    searchHKIDFlow = searchByHKIDResult.searchHKIDFlow;
    custNumOutput = searchByHKIDResult.custNumOutput;
    custNumOutputArray = searchByHKIDResult.custNumOutputArray;
    custNumSearchedHKIDFlow = searchByHKIDResult.custNumSearchedHKIDFlow;
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    
    
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    if (searchHKIDFlow != true) { // false
        if (custNumOutput != ""){ // not search HKID flow , but choosed cust num
            customerNumber = custNumOutput; 
            subscriberNumber = ""; // empty the subr num , use cust num search
        }
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    // Add a placeholder row
        var row = document.all.tblOutstanding.insertRow();
        var cell;
        row.className = "cell-data";
        row.insertCell(0);
        cell = row.insertCell(1);
        cell.colSpan = 8;
        if (process == "issueInvoice") {
            cell.innerHTML = "Starting invoicing......";
        } else if (customerNumber != "") {
            //cell.innerHTML = "Searching customer [" + customerNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingCustomer"), new Object[] {"<SPAN>\" + customerNumber + \"</SPAN>"})%>";
        } else if (subscriberNumber != "") {
            //cell.innerHTML = "Searching subscriber [" + subscriberNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingSubscriber"), new Object[] {"<SPAN>\" + subscriberNumber + \"</SPAN>"})%>";
        } else {
            //cell.innerHTML = "Searching invoice [" + invoiceNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingInvoice"), new Object[] {"<SPAN>\" + invoiceNumber + \"</SPAN>"})%>";
        }

        // Search asynchronously
        if (subscriberNumber != "") {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addBySubscriber&process=" + process + "&handleUser=" + handleUser + "&subscriberNumber=" + subscriberNumber);
        } else if (customerNumber != "") {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addByCustomer&process=" + process + "&handleUser=" + handleUser + "&customerNumber=" + customerNumber);
        } else {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addByInvoice&process=" + process + "&handleUser=" + handleUser + "&invoiceNumber=" + invoiceNumber);
        }
        
        frmSearch.searchCustNo.value = "";   
        frmSearch.searchNumber.value = "";
        frmSearch.searchNumber.focus();
        return false;   
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    } else { //true
        lockButton(btnSearch);
        lockButton(btnSave);
        sendRequestLoop(inputIndex, custNumOutputArray, custNumSearchedHKIDFlow, loopTimeCount, timeCount, process, handleUser, searchHKIDFlow);
        frmSearch.searchCustNo.value = "";   
        frmSearch.searchNumber.value = "";
        frmSearch.searchNumber.focus();
        return false; 
    }
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    */
    // m1 - End
}

function afterSearchByHKID(customerNumber, subscriberNumber, invoiceNumber, process, handleUser, searchByHKIDResult){
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    var searchHKIDFlow = false;
    var custNumOutput = "";
    var custNumOutputArray = "";
    var custNumSearchedHKIDFlow = "";
    var inputIndex = 0;
    var btnSearch = document.getElementById('btnSearch');  
    var btnSave = document.getElementById('btnSave');  
    var loopTimeCount = 5;
    var timeCount = 3000;

    searchHKIDFlow = searchByHKIDResult.searchHKIDFlow;
    custNumOutput = searchByHKIDResult.custNumOutput;
    custNumOutputArray = searchByHKIDResult.custNumOutputArray;
    custNumSearchedHKIDFlow = searchByHKIDResult.custNumSearchedHKIDFlow;
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    if (searchHKIDFlow != true) { // false
        if (custNumOutput != ""){ // not search HKID flow , but choosed cust num
            customerNumber = custNumOutput; 
            subscriberNumber = ""; // empty the subr num , use cust num search
        }
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    // Add a placeholder row
        var row = document.all.tblOutstanding.insertRow();
        var cell;
        row.className = "cell-data";
        row.insertCell(0);
        cell = row.insertCell(1);
        cell.colSpan = 8;
        if (process == "issueInvoice") {
            cell.innerHTML = "Starting invoicing......";
        } else if (customerNumber != "") {
            //cell.innerHTML = "Searching customer [" + customerNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingCustomer"), new Object[] {"<SPAN>\" + customerNumber + \"</SPAN>"})%>";
        } else if (subscriberNumber != "") {
            //cell.innerHTML = "Searching subscriber [" + subscriberNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingSubscriber"), new Object[] {"<SPAN>\" + subscriberNumber + \"</SPAN>"})%>";
        } else {
            //cell.innerHTML = "Searching invoice [" + invoiceNumber + "] ......";
            cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingInvoice"), new Object[] {"<SPAN>\" + invoiceNumber + \"</SPAN>"})%>";
        }

        // Search asynchronously
        if (subscriberNumber != "") {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addBySubscriber&process=" + process + "&handleUser=" + handleUser + "&subscriberNumber=" + subscriberNumber);
        } else if (customerNumber != "") {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addByCustomer&process=" + process + "&handleUser=" + handleUser + "&customerNumber=" + customerNumber);
        } else {
            sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addByInvoice&process=" + process + "&handleUser=" + handleUser + "&invoiceNumber=" + invoiceNumber);
        }
        
        frmSearch.searchCustNo.value = "";   
        frmSearch.searchNumber.value = "";
        frmSearch.searchNumber.focus();
        return false;   
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    } else { //true
        lockButton(btnSearch);
        lockButton(btnSave);
        sendRequestLoop(inputIndex, custNumOutputArray, custNumSearchedHKIDFlow, loopTimeCount, timeCount, process, handleUser, searchHKIDFlow);
        frmSearch.searchCustNo.value = "";   
        frmSearch.searchNumber.value = "";
        frmSearch.searchNumber.focus();
        return false; 
    }
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
}

// Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
function sendRequestLoop(inputIndex, custNumOutputArray, custNumSearchedHKIDFlow, loopTimeCount, timeCount, process, handleUser, searchHKIDFlow) {
    var btnSearch = document.getElementById('btnSearch');  
    var btnSave = document.getElementById('btnSave');
    for (var i = inputIndex; (i < custNumOutputArray.length) && (i < inputIndex + loopTimeCount) ; i++) {
        var row = document.all.tblOutstanding.insertRow();
        var cell;
        row.className = "cell-data";
        row.insertCell(0);
        cell = row.insertCell(1);
        cell.colSpan = 8;
        // only search customer number
        cell.innerHTML = "<%=MessageFormat.format(messageBundle.getString("SearchingCustomer"), new Object[] {"<SPAN>\" + custNumOutputArray[i] + \"</SPAN>"})%>";
        // Search asynchronously
        sendRequest(row, "/servlet/fes.sa.settle.SASettleSearchServlet?action=addByCustomer&process=" + process + "&handleUser=" + handleUser + "&customerNumber=" + custNumOutputArray[i] + "&searchHKIDFlow=" + searchHKIDFlow + "&custNumSearchedHKIDFlow=" + custNumSearchedHKIDFlow);
        if (i == (inputIndex + loopTimeCount - 1)) { // last time of the loop
            setTimeout(function() { sendRequestLoop((inputIndex + loopTimeCount), custNumOutputArray, custNumSearchedHKIDFlow, loopTimeCount, timeCount, process, handleUser, searchHKIDFlow) } , timeCount);
        }
        if (i == (custNumOutputArray.length - 1) ) { // last one
            unlockButton(btnSearch);
            unlockButton(btnSave);
        }
    }
}

var custChooserWin; // m1
function searchByHKID(customerNumber , subscriberNumber , isSelectedCustomerNumber) {
    var custNumOutput = "";
    var custNumOutputArray = "";
    var custNumSearchedHKIDFlow = "";
    var searchHKIDFlow = false;
    
<%  if(settleBean.getUser()!=null){ %>
        if(<%=settleBean.getUser().hasRight("Iuh")%>){ // use hkid to search
            var content = {
                action:                    "searchCustNumByHKIDFlow",
                custNum:                  customerNumber,
                subrNum:                  subscriberNumber,
                isSelectedCustNum: isSelectedCustomerNumber
            };
            dojo.io.bind ({
                url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
                method:  "get",
                content: content,
                error: function(type, data, evt) {
                    alert("Server error : " + data);
                },
                load: function(type, result, evt) {
                    if (result.returnCode == 0 && result.returnMessage == 'SUCCESS'){
                        searchHKIDFlow = true;
                        custNumOutput = result.returnCustNum;
                        custNumOutputArray = custNumOutput.split("|");
                        custNumSearchedHKIDFlow = result.returnCustNumSearched;
                        isCallingSearchHKID = false;
                        searchHKIDResult = { searchHKIDFlow : searchHKIDFlow , 
                                            custNumOutput : custNumOutput ,
                                            custNumOutputArray : custNumOutputArray ,
                                            custNumSearchedHKIDFlow : custNumSearchedHKIDFlow };
                    } else if (result.returnCode == 1 && result.returnMessage == 'SEARCH_SELECT_CUST') {
                        if(window.showModalDialog){ // m1
                        window.returnValue = null;
                        window.dialogWidth = '330px';
                        window.dialogHeight = '300px';
                        }   // m1
                        
                        custNumOutput = result.returnCustNum;
                        if(window.showModalDialog){ // m1
                        window.returnValue = showModalDialog("/jsp/fes/sa/settle/SASettleCustChooserPage.jsp?custNum=" + custNumOutput + "&subrNum=" + subscriberNumber);
                            handleCustChooser(window.returnValue);  // m1 - Begin
                        }else{
                            custChooserWin = window.open("/jsp/fes/sa/settle/SASettleCustChooserPage.jsp?custNum=" + custNumOutput + "&subrNum=" + subscriberNumber, 
                                                            "_custChoose", "width=620px,height=180px");
                            childWins.push(custChooserWin);
                        }
                        /*    
                        if (window.returnValue!=null){
                            var searchByHKIDResult = searchByHKID(window.returnValue , "" , true); //only search the cust num
                            searchHKIDFlow = searchByHKIDResult.searchHKIDFlow;
                            custNumOutput = searchByHKIDResult.custNumOutput;
                            custNumOutputArray = searchByHKIDResult.custNumOutputArray;
                            custNumSearchedHKIDFlow = searchByHKIDResult.returnCustNumSearched;
                        }
                        */    
                        // m1 - End
                    } else if (result.returnCode == 1) {
                        // no action , use the normal flow
                        searchHKIDFlow = false;
                        custNumOutput = result.returnCustNum; 
                        custNumSearchedHKIDFlow = result.returnCustNumSearched;
                        isCallingSearchHKID = false;
                        searchHKIDResult = { searchHKIDFlow : searchHKIDFlow , 
                                            custNumOutput : custNumOutput ,
                                            custNumOutputArray : custNumOutputArray ,
                                            custNumSearchedHKIDFlow : custNumSearchedHKIDFlow };
                    }
                },
                mimetype: "text/json",
                sync: true
            });
        }else{
            isCallingSearchHKID = false;
            searchHKIDResult = { searchHKIDFlow : searchHKIDFlow , 
                                custNumOutput : custNumOutput ,
                                custNumOutputArray : custNumOutputArray ,
                                custNumSearchedHKIDFlow : custNumSearchedHKIDFlow };
        }
<% }else{ %> 
    // m1 - Begin
    isCallingSearchHKID = false;
    searchHKIDResult = { searchHKIDFlow : searchHKIDFlow , 
                        custNumOutput : custNumOutput ,
                        custNumOutputArray : custNumOutputArray ,
                        custNumSearchedHKIDFlow : custNumSearchedHKIDFlow };
    /*
	return { searchHKIDFlow : searchHKIDFlow , 
             custNumOutput : custNumOutput ,
             custNumOutputArray : custNumOutputArray ,
             custNumSearchedHKIDFlow : custNumSearchedHKIDFlow };
    */
    // m1 - End
<% } %>
}

// m1 - Begin
var custChooseInterval;
function handleCustChooser(result){ 
    if (result!=null){
        if(!window.showModalDialog){
            custChooseInterval = setInterval(function(){
                if(custChooserWin == null || (custChooserWin != null && custChooserWin.closed)){
                    searchByHKID(result , "" , true);
                    clearInterval(custChooseInterval);
                }
            }, 100);
        }else{
            var searchByHKIDResult = searchByHKID(result , "" , true); //only search the cust num
        }
    }
}
// m1 - End

function lockButton(button) {
    button.disabled = true;
}

function unlockButton(button) {
    button.disabled = false;
}
// End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)

// Receives notification when issue invoice starts
//Modified by Wilson Hung on 16 AUG 2017 for AccessoriesSales invoicing flow
//function issueInvoice(jobId, customerNumber, subscriberNumber, handleUser) {
//    fraInvoicing.location.replace("/servlet/fes.sa.SAInvoicingServlet?from=settlement&logid=0&customerNumber=" + customerNumber + "&subscriberNumber=" + subscriberNumber + "&handleUser=" + handleUser);
//    divSettlement.style.visibility = "hidden";
//    document.all.fraInvoicing.style.visibility = "visible";
//    var row = jobQueue.item(jobId);
//    document.all.tblOutstanding.deleteRow(row.rowIndex);
//}
function issueInvoice(customerNumber, subscriberNumber, handleUser) {
    if(handleUser == ''){
       alert('Please input handled user.');
       return;
    }
    if(customerNumber == '' && subscriberNumber == ''){
       customerNumber = '00000000';
       subscriberNumber = '00000000';
    }    
    if(subscriberNumber == ''){
       alert('Please input subscriber number.');
       return;
    }
    if(customerNumber == ''){
       customerNumber = '00000000';
    }
    if(subscriberNumber == '00000000'){
       customerNumber = '00000000';
    }
    
    if (customerNumber == "66666666" || customerNumber == "77777777" ||
               customerNumber == "88888888" || customerNumber == "99999999") {
       customerNumber = '00000000';
    }
    if (subscriberNumber == "66666666" || subscriberNumber == "77777777" ||
               subscriberNumber == "88888888" || subscriberNumber == "99999999") {
       subscriberNumber = '00000000';
    }

    if(window.showModalDialog){
    fraInvoicing.location.replace("/servlet/fes.sa.SAInvoicingServlet?from=settlement&logid=0&customerNumber=" + customerNumber + "&subscriberNumber=" + subscriberNumber + "&handleUser=" + handleUser + "&fastAccessories=Y" +"&bulk=' + top.bulk, '_blank', 'left=0, top=0, height=550px, width=780px, toolbar=no'");
    }else{
        fraInvoicing.src="/servlet/fes.sa.SAInvoicingServlet?from=settlement&logid=0&customerNumber=" + customerNumber + "&subscriberNumber=" + subscriberNumber + "&handleUser=" + handleUser + "&fastAccessories=Y" +"&bulk=" + top.bulk;
    }
    divSettlement.style.visibility = "hidden";
    document.all.fraInvoicing.style.visibility = "visible";
}
function goSettlement(logid) {
    window.open('fes.sa.settle.SASettleListServlet?logid=' + logid, null, 'toolbar=no,width=790,height=565,left=0,top=80');
}
//End modified by Wilson Hung on 16 AUG 2017 for AccessoriesSales invoicing flow
//added by John Yue 18 Oct 2006 for accessories sales in cashier settlement
function issueInvoice2(handleUser) {
    if(window.showModalDialog){ // m1
    fraInvoicing.location.replace("/servlet/fes.sa.SAInvoicingServlet?from=settlement2&logid=0&customerNumber=00000000&subscriberNumber=00000000&handleUser=" + handleUser);
    }else{  // m1 - Begin
        fraInvoicing.src="/servlet/fes.sa.SAInvoicingServlet?from=settlement2&logid=0&customerNumber=00000000&subscriberNumber=00000000&handleUser=" + handleUser;
    }   // m1 - End
    divSettlement.style.visibility = "hidden";
    document.all.fraInvoicing.style.visibility = "visible";
}
//End added by John Yue 18 Oct 2006 for accessories sales in cashier settlement

// Receives notification when issue invoicing completes
function issueInvoiceComplete(invoiceNumber, handleUser) {
    divSettlement.style.visibility = "visible";
    document.all.fraInvoicing.style.visibility = "hidden";
    if(window.showModalDialog){ // m1
    fraInvoicing.location.replace("about:blank");
    }else{  // m1 - Begin
        fraInvoicing.src="about:blank";
    }   // m1 - End
    if (invoiceNumber != '') {
        search('', handleUser, invoiceNumber);
    } else {
        frmSearch.searchNumber.focus();
    }
}

// Receives notification when an error occurs
function searchError(jobId, message) {
    var row = jobQueue.item(jobId);
    if(window.showModalDialog){ // m1
    row.cells(0).innerHTML = document.all.templateError.cells(0).innerHTML;
    row.cells(1).innerHTML = "[" + row.cells(1).children(0).innerText + "] " + message;
    }else{  // m1 - Begin
        row.cells[0].innerHTML = document.all.templateError.cells[0].innerHTML;
        row.cells[1].innerHTML = "[" + row.cells[1].children[0].innerText + "] " + message;
    }   // m1 - End
}

// Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
// Receive notification where a NSP customer for HKID flow
function addNSPSubrHkidFlow(jobId , subscriberNumber , customerNumber) {
    var template = document.all.templateNSPSubr;
    var row = jobQueue.item(jobId);
    row.className = 'cell-data';
    for (var i = 0; i < template.cells.length; i++) {
        if (i >= 2) row.insertCell(i);
        if(window.showModalDialog){ // m1
        row.cells(i).align = template.cells(i).align;
        row.cells(i).colSpan = template.cells(i).colSpan;
        row.cells(i).innerHTML = template.cells(i).innerHTML;
        }else{  // m1 - Begin
            row.cells[i].align = template.cells[i].align;
            row.cells[i].colSpan = template.cells[i].colSpan;
            row.cells[i].innerHTML = template.cells[i].innerHTML;
        }   // m1 - End
    }
    if(window.showModalDialog){ // m1
    row.cells(0).innerHTML = document.all.templateNSPSubr.cells(0).innerHTML;
    row.cells(1).children(0).value = customerNumber;
    row.cells(2).children(0).value = subscriberNumber;
    row.cells(3).children(0).value = "<%=messageBundle.getString("NSPsettlementMessage")%>";
    }else{  // m1 - Begin
        row.cells[0].innerHTML = document.all.templateNSPSubr.cells[0].innerHTML;
        row.cells[1].children[0].value = customerNumber;
        row.cells[2].children[0].value = subscriberNumber;
        row.cells[3].children[0].value = "<%=messageBundle.getString("NSPsettlementMessage")%>";
    }   // m1 - End
}

function forwardNSPSettle(row) {
    // m1 - Begin
    /*
    var customerNumber = row.cells(1).children(0).value;
    var subscriberNumber =row.cells(2).children(0).value;
    */
    var customerNumber;
    var subscriberNumber;
    if(window.showModalDialog){
        customerNumber = row.cells(1).children(0).value;
        subscriberNumber =row.cells(2).children(0).value;
    }else{
        customerNumber = row.cells[1].children[0].value;
        subscriberNumber =row.cells[2].children[0].value;
    }
    // m1 - End
    if(window.showModalDialog){ // m1
    window.showModalDialog("/servlet/fes.sa.prepaidsettle/PrepaidSettleServlet?action=init&subrNum="+subscriberNumber+"&custNum="+customerNumber, 
                            null, "dialogWidth:750px; dialogHeight:600px; dialogLeft:0px; dialogTop:0px;");
                                    
    }else{  // m1 - Begin
        var initPPSet = window.open("/servlet/fes.sa.prepaidsettle/PrepaidSettleServlet?action=init&subrNum="+subscriberNumber+"&custNum="+customerNumber, 
                                    "_initPPSettle", "width=750px,height=600px");
        childWins.push(initPPSet);
    }   // m1 - End
}

// End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)

var isEnableCell4Filp = true;   // m1
// Receives notification when a customer is added
function addCustomer(jobId, customer) {
    // Check whether the customer is already added
    if (customers.exists(customer.toString())) {
        searchError(jobId, "<%=MessageFormat.format(messageBundle.getString("IncludedInCustomer"), new Object[] {"\" + customer.customerNumber + \""})%>");
        return false;
    }

    // Add the customer record
    //customers.add(customer.customerNumber, customer);
    customers.add(customer.toString(), customer);
    //customer.invoices = new Array();
    //customer.expanded = false;

    // Add a payment object
    var payment = createPaymentObject(customer.customerNumber, customer.outstandingAmount, customer.paymentAmount, customer.settleAmount, true);
    //payments.add(customer.customerNumber, payment);
    payments.add(customer.toString(), payment);

    // Display the customer record
    var template = document.all.templateCustomer;
    var row = jobQueue.item(jobId);
    row.className = 'cell-data';
    for (var i = 0; i < template.cells.length; i++) {
        if (i >= 2) row.insertCell(i);
        if(window.showModalDialog){ // m1
        row.cells(i).align = template.cells(i).align;
        row.cells(i).colSpan = template.cells(i).colSpan;
        row.cells(i).innerHTML = template.cells(i).innerHTML;
        }else{  // m1 - Begin
            row.cells[i].align = template.cells[i].align;
            row.cells[i].colSpan = template.cells[i].colSpan;
            row.cells[i].innerHTML = template.cells[i].innerHTML;
            if(i == 1 || i == 5 || i == 2){
                row.cells[i].style.textWrap = "nowrap"
            }
        }   // m1 - End
    }
    if(window.showModalDialog){ // m1
    row.cells(0).children(0).value = customer.toString();
    row.cells(1).children(0).value = customer.customerNumber;
    row.cells(2).children(0).value = customer.customerName;
    if ((customer.attribute & <%=SASettleConstant.CUST_ATTR_ATP%>) != 0) {
        row.cells(2).children(1).style.display = "inline";
        row.cells(2).children(0).style.pixelWidth -= (row.cells(2).children(1).style.pixelWidth + 20);
    }
    }else{  // m1 - Begin
        row.cells[0].children[0].value = customer.toString();
        row.cells[1].children[0].value = customer.customerNumber;
        row.cells[2].children[0].value = customer.customerName;
        if ((customer.attribute & <%=SASettleConstant.CUST_ATTR_ATP%>) != 0) {
            row.cells[2].children[1].style.display = "inline";
            row.cells[2].children[0].clientWidth -= (row.cells[2].children[1].clientWidth + 20);
            row.cells[2].children[0].style.width = row.cells[2].children[0].clientWidth + "px";
        }
    }   // m1 - End
    // Added by William Tam on 24 Jun 2009, for BM Security
// Modified by Ken Cheng on 18 Aug 2016, for finance processing time
    <% if(settleBean.getUser()!=null){ %>
        if(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%>){ 
            if(customer.startRequest == <%=SASettleConstant.SEARCH_CUST_ADDED%>){
                if(window.showModalDialog){ // m1
                row.cells(1).children(2).style.display = "inline";
                }else{  // m1 - Begin
                    row.cells[1].children[2].style.display = "inline";
                }   // m1 - End
            }
            if(customer.startRequest == <%=SASettleConstant.SEARCH_SUBR_ADDED%>){
                //row.cells(1).children(1).style.visibility = "hidden";
                var addSubrButton = document.createElement("button");
                addSubrButton.value = "+";
                addSubrButton.style.width = "20px";
                addSubrButton.className = "thin-button";
                addSubrButton.onclick = function () {
                    addSubscriber(customer, row);
                }
                if(window.showModalDialog){ // m1
                row.cells(1).replaceChild(addSubrButton,row.cells(1).children(1));
                }else{  // m1 - Begin
                    addSubrButton.textContent = "+";
                    row.cells[1].replaceChild(addSubrButton,row.cells[1].children[1]);
                }   // m1 - End
            }
            else{
                if(window.showModalDialog){ // m1
                row.cells(1).children(1).style.display = "none";
                }else{  // m1 - Begin
                    row.cells[1].children[1].style.display = "none";
                }   // m1 - End
            }
        }
    <% } %>
    // End of added by William Tam on 24 Jun 2009, for BM Security
    if(window.showModalDialog){ // m1
    row.cells(3).children(0).value = customer.nextDD;
    }else{  // m1 - Begin
        row.cells[3].children[0].value = customer.nextDD;
    }   // m1 - End
    // Added by William Tam on 24 Jun 2009, for BM Security
    <% if(settleBean.getUser()!=null){ %>
    if(!(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%>) && customer.startRequest == <%=SASettleConstant.SEARCH_CUST_ADDED%>){
        if(window.showModalDialog){ // m1
    row.cells(4).children(1).value = "(" + customer.expectedInvoiceCount + ")";
        }else{  // m1 - Begin
            row.cells[4].children[1].value = "(" + customer.expectedInvoiceCount + ")";
        }   // m1 - End
    }
    <% } %>
    // End of added by William Tam on 24 Jun 2009, for BM Security
    if (customer.expectedInvoiceCount == 0) {
        if(window.showModalDialog){ // m1
        row.cells(4).children(0).style.filter = "gray()";
        row.cells(4).children(0).disabled = true;
        }else{  // m1 - Begin
            //row.cells[4].children[0].style.filter = "gray()";
            row.cells[4].children[0].style.webkitFilter = "grayscale(100%)";
            row.cells[4].children[0].disabled = true;
        }   // m1 - End
    } else {
        if(window.showModalDialog){ // m1
        row.cells(4).children(0).filters.item("flipv").enabled = false;
        }else{  // m1 - Begin
            //row.cells[4].children[0].filters.item("flipv").enabled = false;
            isEnableCell4Filp = false;
        }   // m1 - End
    }
    if(window.showModalDialog){ // m1
    row.cells(5).children(1).value = FormatCurr(customer.paymentAmount);
    row.cells(5).children(1).setAttribute("oldValue", row.cells(5).children(1).value);
    row.cells(6).children(0).value = FormatCurr(customer.outstandingAmount);
    if (customer.creditAmount > 0) {
        row.cells(6).children(1).style.display = "inline";
        row.cells(6).children(1).value = "(" + FormatCurr(customer.creditAmount) + ")";
    }
    row.cells(7).children(0).value = FormatCurr(customer.settleAmount);
    row.cells(7).children(0).setAttribute("oldValue", row.cells(7).children(0).value);
    }else{  // m1 - Begin
        row.cells[5].children[1].value = FormatCurr(customer.paymentAmount);
        row.cells[5].children[1].setAttribute("oldValue", row.cells[5].children[1].value);
        row.cells[6].children[0].value = FormatCurr(customer.outstandingAmount);
        if (customer.creditAmount > 0) {
            row.cells[6].children[1].style.display = "inline";
            row.cells[6].children[1].value = "(" + FormatCurr(customer.creditAmount) + ")";
        }
        row.cells[7].children[0].value = FormatCurr(customer.settleAmount);
        row.cells[7].children[0].setAttribute("oldValue", row.cells[7].children[0].value);
    }   // m1 - End
    // Refresh the total amount
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) + customer.outstandingAmount
    );
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) + customer.settleAmount
    );

    <% if(settleBean.getUser()!=null){ %>
        if(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%> && customer.startRequest == <%=SASettleConstant.SEARCH_SUBR_ADDED%>){
            promptAcctMgr(customer);
        }
    <% } %>
    autoAllocate(row, customer, false);

    // Auto-expand
    if (customer.expectedInvoiceCount > 0) {
        toggleCustomer(row);
    }
    
    // Added by Billy Pang on 25/5/2017 for adding Kiosk OS 
    // Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk
    // document.all.kioskCustAmount.value = FormatCurr(customer.kioskCustAmount);
    // document.all.kioskSubrAmount.value = FormatCurr(customer.kioskSubrAmount);
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    if ( customer.kioskCustAmount != "" && customer.kioskSubrAmount != "" ) {
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
        document.all.kioskCustAmount.value = customer.kioskCustAmount; 
        document.all.kioskSubrAmount.value = customer.kioskSubrAmount; 
    // Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    }
    // End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260)
    // End Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk   
    // End Added by Billy Pang on 25/5/2017 for adding Kiosk OS
    
}
// End modified by Ken Cheng on 18 Aug 2016, for finance processing time
// m1 - Begin
var tmpCustomer;
var tmpCustomerRow;
// m1 - End
// Added by William Tam on 24 Jun 2009, for BM Security
function addSubscriber(customer, customerRow) {
    var result;
    if(window.showModalDialog){ // m1
        result = window.showModalDialog("/jsp/fes/sa/settle/SASettleAddSubrNum.jsp",
                      {customer: customer},
                     "dialogTop:100px; dialogLeft:150px; dialogWidth:250px; dialogHeight:400px");
        handleSASettleAddSubrNum(result, customer, customerRow);
    }else{  // m1 - Begin
        tmpCustomer = customer;
        tmpCustomerRow = customerRow;
        result = window.open("/jsp/fes/sa/settle/SASettleAddSubrNum.jsp", "_setAddSubr", "width=510px,height=410px");
        childWins.push(result);
    }   // m1 - End
    // m1 - Begin
    /*
    if(result != null){
        customer.moreSubscriberNumbers = result.subrList;
        var payment = payments.item(customer.customerNumber);
        var existInvoiceCount = customer.invoices.length;
        // clear all existing invoices
        customer.invoices = new Array();
        
        // Refresh the total amount
        document.all.outstandingTotal.value = FormatCurr(
            new Number(document.all.outstandingTotal.value) - customer.outstandingAmount
        );
        document.all.settleTotal.value = FormatCurr(
            new Number(document.all.settleTotal.value) - customer.settleAmount
        );

        // remove all invoices of customer
        if (customer.expanded) {
            for (var i = existInvoiceCount; i >= 1; i--) {
                document.all.tblOutstanding.deleteRow(customerRow.rowIndex + i);
            }
        }

        // search invoices
        var content = {
            action:             "addMoreSubrNum",
            custNum:            customer.customerNumber,
            subrNum:            cloneArray(customer.moreSubscriberNumbers)
        };
    
        dojo.io.bind({
            url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
            method:  "get",
            content: content,
            error: function(type, data, evt) {
                alert("Server error.");
            },
            load: function(type, result, evt) {
                if(result != null){
                    // update customer
                    customer.outstandingAmount = result.outstandingAmount;
                    customer.paymentAmount = result.paymentAmount;
                    customer.settleAmount = result.settleAmount;
                    customer.creditAmount = result.creditAmount;
                    customer.expectedInvoiceCount = result.expectedInvoiceCount;
                    customer.expanded = false;
                    customer.invoices = new Array();
                    customer.invoices.length = 0;
                    for (var i = 0; i < result.invoices.length; i++) {
                        invoice = customer.invoices[customer.invoices.length++] = new Invoice(result.invoices[i].customerNumber, result.invoices[i].subscriberNumber, result.invoices[i].invoiceNumber);
                        invoice.invoiceDate = result.invoices[i].invoiceDate;
                        invoice.systemInd = result.invoices[i].systemInd;
                        invoice.chargeType = result.invoices[i].chargeType;
                        invoice.status = result.invoices[i].status;
                        invoice.discReason = result.invoices[i].discReason;
                        invoice.discDate = result.invoices[i].discDate;
                        invoice.outstandingAmount = result.invoices[i].outstandingAmount;
                        invoice.settleAmount = result.invoices[i].settleAmount;
                        invoice.attribute = result.invoices[i].attribute;
                        invoice.invoiceType = result.invoices[i].invoiceType;
                    }
                    // update payment
                    payment.outstandingAmount = customer.outstandingAmount;
                    payment.paymentAmount = customer.paymentAmount;
                    payment.settleAmount = customer.settleAmount;
                }
            },
            mimetype: "text/json",
            sync: true
            }
        );

        // Refresh customer row
        customerRow.cells(5).children(1).value = FormatCurr(customer.paymentAmount);
        customerRow.cells(5).children(1).setAttribute("oldValue", customerRow.cells(5).children(1).value);
        customerRow.cells(6).children(0).value = FormatCurr(customer.outstandingAmount);
        if (customer.creditAmount > 0) {
            customerRow.cells(6).children(1).style.display = "inline";
            customerRow.cells(6).children(1).value = "(" + FormatCurr(customer.creditAmount) + ")";
        }
        customerRow.cells(7).children(0).value = FormatCurr(customer.settleAmount);
        customerRow.cells(7).children(0).setAttribute("oldValue", customerRow.cells(7).children(0).value);

        // Refresh the total amount
        document.all.outstandingTotal.value = FormatCurr(
            new Number(document.all.outstandingTotal.value) + customer.outstandingAmount
        );
        document.all.settleTotal.value = FormatCurr(
            new Number(document.all.settleTotal.value) + customer.settleAmount
        );

        autoAllocate(customerRow, customer, false);

        // Auto-expand
        if (customer.expectedInvoiceCount > 0) {
            toggleCustomer(customerRow);
        }
    }
    */
    // m1 - End
}
// End of Added by William Tam on 24 Jun 2009, for BM Security 

function handleSASettleAddSubrNum(result, customer, customerRow){
        if(result != null){
        customer.moreSubscriberNumbers = result.subrList;
        var payment = payments.item(customer.customerNumber);
        var existInvoiceCount = customer.invoices.length;
        // clear all existing invoices
        customer.invoices = new Array();
        
        // Refresh the total amount
        document.all.outstandingTotal.value = FormatCurr(
            new Number(document.all.outstandingTotal.value) - customer.outstandingAmount
        );
        document.all.settleTotal.value = FormatCurr(
            new Number(document.all.settleTotal.value) - customer.settleAmount
        );

        // remove all invoices of customer
        if (customer.expanded) {
            for (var i = existInvoiceCount; i >= 1; i--) {
                document.all.tblOutstanding.deleteRow(customerRow.rowIndex + i);
            }
        }

        // search invoices
        var content = {
            action:             "addMoreSubrNum",
            custNum:            customer.customerNumber,
            subrNum:            cloneArray(customer.moreSubscriberNumbers)
        };
    
        dojo.io.bind({
            url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
            method:  "get",
            content: content,
            error: function(type, data, evt) {
                alert("Server error.");
            },
            load: function(type, result, evt) {
                if(result != null){
                    // update customer
                    customer.outstandingAmount = result.outstandingAmount;
                    customer.paymentAmount = result.paymentAmount;
                    customer.settleAmount = result.settleAmount;
                    customer.creditAmount = result.creditAmount;
                    customer.expectedInvoiceCount = result.expectedInvoiceCount;
                    customer.expanded = false;
                    customer.invoices = new Array();
                    customer.invoices.length = 0;
                    for (var i = 0; i < result.invoices.length; i++) {
                        invoice = customer.invoices[customer.invoices.length++] = new Invoice(result.invoices[i].customerNumber, result.invoices[i].subscriberNumber, result.invoices[i].invoiceNumber);
                        invoice.invoiceDate = result.invoices[i].invoiceDate;
                        invoice.systemInd = result.invoices[i].systemInd;
                        invoice.chargeType = result.invoices[i].chargeType;
                        invoice.status = result.invoices[i].status;
                        invoice.discReason = result.invoices[i].discReason;
                        invoice.discDate = result.invoices[i].discDate;
                        invoice.outstandingAmount = result.invoices[i].outstandingAmount;
                        invoice.settleAmount = result.invoices[i].settleAmount;
                        invoice.attribute = result.invoices[i].attribute;
                        invoice.invoiceType = result.invoices[i].invoiceType;
                    }
                    // update payment
                    payment.outstandingAmount = customer.outstandingAmount;
                    payment.paymentAmount = customer.paymentAmount;
                    payment.settleAmount = customer.settleAmount;
                }
            },
            mimetype: "text/json",
            sync: true
            }
        );

        // Refresh customer row
        if(window.showModalDialog){ // m1
        customerRow.cells(5).children(1).value = FormatCurr(customer.paymentAmount);
        customerRow.cells(5).children(1).setAttribute("oldValue", customerRow.cells(5).children(1).value);
        customerRow.cells(6).children(0).value = FormatCurr(customer.outstandingAmount);
        if (customer.creditAmount > 0) {
            customerRow.cells(6).children(1).style.display = "inline";
            customerRow.cells(6).children(1).value = "(" + FormatCurr(customer.creditAmount) + ")";
        }
        customerRow.cells(7).children(0).value = FormatCurr(customer.settleAmount);
        customerRow.cells(7).children(0).setAttribute("oldValue", customerRow.cells(7).children(0).value);
        }else{  // m1 - Begin
            customerRow.cells[5].children[1].value = FormatCurr(customer.paymentAmount);
            customerRow.cells[5].children[1].setAttribute("oldValue", customerRow.cells[5].children[1].value);
            customerRow.cells[6].children[0].value = FormatCurr(customer.outstandingAmount);
            if (customer.creditAmount > 0) {
                customerRow.cells[6].children[1].style.display = "inline";
                customerRow.cells[6].children[1].value = "(" + FormatCurr(customer.creditAmount) + ")";
            }
            customerRow.cells[7].children[0].value = FormatCurr(customer.settleAmount);
            customerRow.cells[7].children[0].setAttribute("oldValue", customerRow.cells[7].children[0].value);
        }   // m1 - End

        // Refresh the total amount
        document.all.outstandingTotal.value = FormatCurr(
            new Number(document.all.outstandingTotal.value) + customer.outstandingAmount
        );
        document.all.settleTotal.value = FormatCurr(
            new Number(document.all.settleTotal.value) + customer.settleAmount
        );

        autoAllocate(customerRow, customer, false);

        // Auto-expand
        if (customer.expectedInvoiceCount > 0) {
            toggleCustomer(customerRow);
        }
    }
}

// Toggle view of a customer record
function toggleCustomer(row) {
    //var customer = customers.item(row.cells(1).children(0).value);
    // m1 - Begin
    var customer;
    var tmpRowVal;
    if(window.showModalDialog){
        tmpRowVal = row.cells(0).children(0).value;
    }else{
        tmpRowVal = row.cells[0].children[0].value;
    }
    /*var customer = customers.item(row.cells(0).children(0).value);*/
    if(window.showModalDialog){
        customer = customers.item(row.cells(0).children(0).value);
    }else{
        customer = customers.item(row.cells[0].children[0].value);
    }
    // m1 - End
    customer.expanded = !customer.expanded;
    if(window.showModalDialog){ // m1
    row.cells(4).children(0).filters.item("flipv").enabled = customer.expanded;
    }else{  // m1 - Begin
        //row.cells[4].children[0].filters.item("flipv").enabled = customer.expanded;
        isEnableCell4Filp = customer.expanded;
    }   // m1 - End
    if (customer.expanded) {
        expand(row);
    } else {
        collapse(row);
    }
}

// Expand a customer record
function expand(row, startIndex) {
    // m1 - Begin
    /*var key = row.cells(0).children(0).value;*/
    var key;
    if(window.showModalDialog){
        key = row.cells(0).children(0).value;
    }else{
        key = row.cells[0].children[0].value;
        row.cells[4].children[0].style.webkitTransform="scaleY(-1)";
    }
    // m1- End
    //var customerNumber = row.cells(1).children(0).value;
    //var customer = customers.item(customerNumber);
    var customer = customers.item(key);

    if (startIndex == null) startIndex = 0;
    //pool.add(customerNumber, {
    pool.add(customer.customerNumber, {
        "row"     : row,
        "invoices": customer.invoices,
        "index"   : startIndex,
        "action"  : "expand"}
    );
/*
    if (customer.invoices.length == 0) {
        // Add a placeholder row
        var r = document.all.tblOutstanding.insertRow(row.rowIndex + 1);
        var cell;
        r.className = "cell-data2";
        r.insertCell(0);
        cell = r.insertCell(1);
        cell.colSpan = 8;
        cell.innerHTML = "<%=messageBundle.getString("SearchingOsItem")%>";
        sendRequest(row, "../servlet/fes.sa.SASettleSearchServlet?action=expandCustomer&customerNumber=" + customerNumber);
    } else {
        showInvoices(customerNumber, customer.subscriberNumber, false);
    }
*/
    showInvoices(customer.customerNumber, customer.subscriberNumber, false);
}
/*
// Receives notification when a customer is expanded
function expandCustomer(jobId, invoices) {
    var row = jobQueue.item(jobId);
    if (row.cells.length == 0) return; // cancelled

    var customerNumber = row.cells(1).children(0).value;
    var customer = customers.item(customerNumber);

    customer.invoices = invoices;
    pool.item(customerNumber).invoices = invoices;
    document.all.tblOutstanding.deleteRow(row.rowIndex + 1);
    autoAllocate(row, customer, false);
    showInvoices(customerNumber, customer.subscriberNumber, false);
}
*/
// Show invoices of a customer row
function showInvoices(customerNumber, subscriberNumber, selectedSubscriberFound) {
    var array = pool.item(customerNumber);
    var parentRow = array.row;
    var invoices = array.invoices;
    var index = array.index;

    if (array.index == invoices.length || array.action == "interrupt") {
        pool.remove(customerNumber);
        return;
    }

    if (array.action == "expand") {
        // Build the row
        array.index++
        var template = document.all.templateInvoice;
        var row = document.all.tblOutstanding.insertRow(parentRow.rowIndex + index + 1);
        row.className = 'cell-data2';
        for (var j = 0; j < template.cells.length; j++) {
            row.insertCell(j);
            if(window.showModalDialog){ // m1
            row.cells(j).align = template.cells(j).align;
            row.cells(j).innerHTML = template.cells(j).innerHTML;
            }else{  // m1 - Begin
                row.cells[j].align = template.cells[j].align;
                row.cells[j].innerHTML = template.cells[j].innerHTML;
            }   // m1 - End
        }

        // Display contents
        var invoice = invoices[index];
/*      
        if (subscriberNumber == "") { // Set default of printed subscriber number to the first subscriber number
        //if (subscriberNumber == "" && !isNaN(parseInt(invoice.subscriberNumber))) { // Set default of printed subscriber number to the first subscriber number
            subscriberNumber = invoice.subscriberNumber;
            setSubscriberForPrint(customerNumber, subscriberNumber);
        }
*/
        // Modified by William Tam on 24 Jun 2009, for BM Security
        if(invoice.invoiceType == "E" || invoice.invoiceType == "W" || invoice.invoiceType == "O" || invoice.invoiceType == "D"){
            // Modified by William Tam on 8 Aug 2011
            //row.cells(1).removeChild(row.cells(1).children(1));
            if(window.showModalDialog){ // m1
            row.cells(1).removeChild(row.cells(1).children(2));
            }else{  // m1 - Begin
                row.cells[1].removeChild(row.cells[1].children[2]);
            }   // m1 - End
            // End of Modified by William Tam on 8 Aug 2011
        }

        if(invoice.invoiceType == "E"){
            /*
            row.cells(1).innerHTML = "<input type=button class=thin-button value='D'>";
            row.cells(1).children(0).onclick = function() { 
                removeExcludeNo(parentRow, row, invoice);
            };
            */
            var newChild = document.createElement("button");
            newChild.className = "thin-button";
            newChild.value = "D";
            newChild.onclick = function() { 
                removeExcludeNo(parentRow, row, invoice);
            };
            row.cells(1).appendChild(newChild);
        } else if (invoice.invoiceType == "W" || invoice.invoiceType == "O") {
            if(window.showModalDialog){ // m1
            // Modified by William Tam on 8 Aug 2011
            row.cells(1).removeChild(row.cells(1).children(1)); 
            //row.cells(1).removeChild(row.cells(1).children(0)); 
            // End of Modified by William Tam on 8 Aug 2011
            }else{  // m1- Begin
                row.cells[1].removeChild(row.cells[1].children[1]); 
            }   // m1- End
            var newChild = document.createElement("button");
            newChild.className= "thin-button";
            newChild.value = "i";
            newChild.style.width = "15px"
            newChild.onclick = function() { 
                if(window.showModalDialog){ // m1
                showAllHandsetInvoice(invoice, row.cells(row.cells.length-1).children(0));
                }else{  // m1 - Begin
                    showAllHandsetInvoice(invoice, row.cells[row.cells.length-1].children[0]);
                }   // m1 - End
            };
            if(window.showModalDialog){ // m1
            row.cells(1).appendChild(newChild);
            }else{  // m1- Begin
                newChild.textContent = "i";
                row.cells[1].appendChild(newChild);
            }   // m1- End
        } else if (invoice.invoiceType == "D")  {
            // do nothing
        } else {
        // End of Modified by William Tam on 24 Jun 2009, for BM Security
        if (!isNaN(parseInt(invoice.subscriberNumber))) {
            // Modified by William Tam on 8 Aug 2011
            /*
            row.cells(1).children(1).outerHTML = row.cells(1).children(1).outerHTML.substring(0, row.cells(1).children(1).outerHTML.length - 1) +
                " NAME=p" + customerNumber + " VALUE=" + invoice.subscriberNumber + ">";
            */
            if(window.showModalDialog){ // m1
            row.cells(1).children(2).outerHTML = row.cells(1).children(2).outerHTML.substring(0, row.cells(1).children(2).outerHTML.length - 1) +
            " NAME=p" + customerNumber + " VALUE=" + invoice.subscriberNumber + ">";
            row.cells(1).children(1).style.width = "30px";
            row.cells(1).children(0).style.display = "inline";
            row.cells(1).children(0).onclick = function() {
                showModalDialog("/jsp/fes/sa/settle/SASettleEmailAddr.jsp?invoiceNo="+invoice.invoiceNumber+
                                "&subrNum="+invoice.subscriberNumber+"&custNum="+invoice.customerNumber, null,
                                "dialogWidth:480px; dialogHeight:150px; dialogLeft: 150px; dialogTop: 250px;");
            }
            }else{  // m1 - Begin
                row.cells[1].children[2].outerHTML = row.cells[1].children[2].outerHTML.substring(0, row.cells[1].children[2].outerHTML.length - 1) +
                " NAME=p" + customerNumber + " VALUE=" + invoice.subscriberNumber + ">";
                row.cells[1].children[1].style.width = "30px";
                row.cells[1].children[0].style.display = "inline";
                row.cells[1].children[0].onclick = function() {
                    var emailAddr = window.open("/jsp/fes/sa/settle/SASettleEmailAddr.jsp?invoiceNo="+invoice.invoiceNumber+
                                    "&subrNum="+invoice.subscriberNumber+"&custNum="+invoice.customerNumber, "_setlEmailAddr",
                                    "width=500px,height=150px");
                    childWins.push(emailAddr);
                }
            }
            // m1 - End
             // End of Modified by William Tam on 8 Aug 2011
        } else {
            // Modified by William Tam on 8 Aug 2011
            //row.cells(1).children(1).disabled = true;
            if(window.showModalDialog){ // m1
            row.cells(1).children(2).disabled = true;
            row.cells(1).children(1).style.width = "30px";
            row.cells(1).children(0).style.display = "inline";
            row.cells(1).children(0).onclick = function() {
                showModalDialog("/jsp/fes/sa/settle/SASettleEmailAddr.jsp?invoiceNo="+
                                "&subrNum="+subscriberNumber+"&custNum="+customerNumber, null,
                                "dialogWidth:480px; dialogHeight:150px; dialogLeft: 150px; dialogTop: 250px;");
            }
            }else{  // m1 - Begin
                row.cells[1].children[2].disabled = true;
                row.cells[1].children[1].style.width = "30px";
                row.cells[1].children[0].style.display = "inline";
                row.cells[1].children[0].onclick = function() {
                    var emailAddr = window.open("/jsp/fes/sa/settle/SASettleEmailAddr.jsp?invoiceNo="+
                                    "&subrNum="+subscriberNumber+"&custNum="+customerNumber, "_setlEmailAddr",
                                    "width=500px,height=150px");
                    childWins.push(emailAddr);
                }
            }   // m1 - End
            // End of Modified by William Tam on 8 Aug 2011
        }
        // Modified by William Tam on 24 Jun 2009, for BM Security
        }
        // End of Modified by William Tam on 24 Jun 2009, for BM Security
        if(window.showModalDialog){ // m1
        row.cells(2).children(0).value = invoice.subscriberNumber;
        }else{  // m1 - Begin
            row.cells[2].children[0].value = invoice.subscriberNumber;
        }   // m1 - End
        if (invoice.subscriberNumber == "INTERIM" && (invoice.attribute & <%=SASettleConstant.INV_ATTR_INSTALMENT%>) != 0) {
            if(window.showModalDialog){ // m1
            row.cells(2).children(1).style.display = "inline";
            row.cells(2).children(0).style.pixelWidth -= (row.cells(2).children(1).style.pixelWidth + 20);
            }else{  // m1 - Begin
                row.cells[2].children[1].style.display = "inline";
                row.cells[2].children[0].clientWidth -= (row.cells[2].children[1].clientWidth + 20);
                row.cells[2].children[0].style.width = row.cells[2].children[0].clientWidth + "px";
            }   // m1 - End
            setTimeout("alert('<%=messageBundle.getString("InterimInstalmentReferContactHistory")%>');", 1);
        }
        // Added by William Tam on 15 Jan 2009, for Bounced Cheque
        if (invoice.subscriberNumber == "Acpt Cash Only") {
            if(window.showModalDialog){ // m1
            row.cells(2).children(0).style.pixelWidth += 15;
            }else{  // m1 - Begin
                row.cells[2].children[0].clientWidth += 15;
                row.cells[2].children[0].style.width = row.cells[2].children[0].clientWidth + "px";
            }   // m1 - End
        }
        // End of Added by William Tam on 15 Jan 2009, for Bounced Cheque
        if (!selectedSubscriberFound && subscriberNumber == invoice.subscriberNumber) {
            selectedSubscriberFound = true;
            // Modified by William Tam on 8 Aug 2011
            //row.cells(1).children(1).checked = true;
            if(window.showModalDialog){ // m1
            row.cells(1).children(2).checked = true;
            }else{  // m1 - Begin
                row.cells[1].children[2].checked = true;
            }   // m1 - End
            // End of Modified by William Tam on 8 Aug 2011
        }
        // Added by William Tam on 24 Jun 2009, for BM Security
        var cellIndex = 2;
        if(invoice.invoiceType == "W" || invoice.invoiceType == "O" || invoice.invoiceType == "D"){ 
            if(window.showModalDialog){ // m1
            row.cells(cellIndex).colSpan = 2;
            row.cells(cellIndex).children(0).style.pixelWidth = row.cells(cellIndex).children(0).style.pixelWidth +
                                                                row.cells(cellIndex+1).children(0).style.pixelWidth;
            }else{  // m1 - Begin
                row.cells[cellIndex].colSpan = 2;
                row.cells[cellIndex].children[0].style.width = row.cells[cellIndex].children[0].clientWidth +
                                                                row.cells[cellIndex+1].children[0].clientWidth + "px";
            }   // m1 - End
            row.deleteCell(cellIndex+1);
        } else {
            if(window.showModalDialog){ // m1
            row.cells(++cellIndex).children(0).value = invoice.invoiceNumber;
            }else{  // m1 - Begin
                row.cells[++cellIndex].children[0].value = invoice.invoiceNumber;
            }   // m1 - End
        }    
        if(window.showModalDialog){ // m1
        row.cells(++cellIndex).children(0).value = invoice.invoiceDate;
        row.cells(++cellIndex).innerHTML = invoice.status;
        row.cells(++cellIndex).innerHTML = invoice.discReason + (invoice.discDate==""?"":(" (" + invoice.discDate + ")"));
        row.cells(++cellIndex).children(0).value = FormatCurr(invoice.outstandingAmount);
        row.cells(++cellIndex).children(0).value = FormatCurr(invoice.settleAmount);
        row.cells(cellIndex).children(0).setAttribute("oldValue", FormatCurr(invoice.settleAmount));
        if(invoice.invoiceType == "E" || invoice.invoiceType == "W" || invoice.invoiceType == "O") {
            row.cells(cellIndex).children(0).readOnly = true;
        }
        }else{  // m1 - Begin
            row.cells[++cellIndex].children[0].value = invoice.invoiceDate;
            row.cells[++cellIndex].innerHTML = invoice.status;
            row.cells[++cellIndex].innerHTML = invoice.discReason + (invoice.discDate==""?"":(" (" + invoice.discDate + ")"));
            row.cells[++cellIndex].children[0].value = FormatCurr(invoice.outstandingAmount);
            row.cells[++cellIndex].children[0].value = FormatCurr(invoice.settleAmount);
            row.cells[cellIndex].children[0].setAttribute("oldValue", FormatCurr(invoice.settleAmount));
            if(invoice.invoiceType == "E" || invoice.invoiceType == "W" || invoice.invoiceType == "O") {
                row.cells[cellIndex].children[0].readOnly = true;
            }
        }   // m1- End
        // End of added by William Tam on 24 Jun 2009, for BM Security
        /*
        row.cells(3).children(0).value = invoice.invoiceNumber;
        row.cells(4).children(0).value = invoice.invoiceDate;
        row.cells(5).innerHTML = invoice.status;
        if (invoice.discReason != "") {
            // Modified by William Tam on 24 Jun 2009, for BM security
            //row.cells(6).innerHTML = invoice.discReason + " (" + invoice.discDate + ")";
            row.cells(6).innerHTML = invoice.discReason + (invoice.discDate!=""?(" (" + invoice.discDate + ")"):"");
            // End of Modified by William Tam on 24 Jun 2009, for BM security
        }
        row.cells(7).children(0).value = FormatCurr(invoice.outstandingAmount);
        row.cells(8).children(0).value = FormatCurr(invoice.settleAmount);
        row.cells(8).children(0).setAttribute("oldValue", FormatCurr(invoice.settleAmount));
        */
    }

    // Done / Interrupted / Collapsed / Remove / Show Next
    if (array.index == invoices.length || array.action == "interrupt") {
        pool.remove(customerNumber);
    } else if (array.action == "collapse") {
        pool.remove(customerNumber);
        collapse(parentRow, array.index);
    } else if (array.action == "remove") {
        pool.remove(customerNumber);
        removeCustomer(parentRow, array.index);
    } else if (array.action == "expand" && array.index % 3 == 0) {
        setTimeout("showInvoices('" + customerNumber + "', '" + subscriberNumber + "', " + selectedSubscriberFound + ")", 1);
    } else {
        showInvoices(customerNumber, subscriberNumber, selectedSubscriberFound);
    }
}

var isEnableCell5Filp = true;   // m1
// Receives notification when an ungrouped invoice is added
function addInvoice(jobId, invoice) {
    // Check whether the invoice is already added
    //if (invoices.exists(invoice.invoiceNumber)) {
    if (invoices.exists(invoice.toString())) {
        searchError(jobId, "<%=messageBundle.getString("InvoiceAlreadyIncluded")%>");
        return false;
    }

    // Add the invoice record
    //invoices.add(invoice.invoiceNumber, invoice);
    invoices.add(invoice.toString(), invoice);

    // Add a payment object
    var payment = createPaymentObject(invoice.invoiceNumber, invoice.outstandingAmount, invoice.paymentAmount, invoice.settleAmount, false);
    //payments.add(invoice.invoiceNumber, payment);
    payments.add(invoice.toString(), payment);

    // Display the invoice record
    var template = document.all.templatePrepaid;
    var row = jobQueue.item(jobId);
    row.className = 'cell-data';
    for (var i = 0; i < template.cells.length; i++) {
        if (i >= 2) row.insertCell(i);
        if(window.showModalDialog){ // m1
        row.cells(i).align = template.cells(i).align;
        row.cells(i).colSpan = template.cells(i).colSpan;
        row.cells(i).innerHTML = template.cells(i).innerHTML;
        }else{  // m1 - Begin
            row.cells[i].align = template.cells[i].align;
            row.cells[i].colSpan = template.cells[i].colSpan;
            row.cells[i].innerHTML = template.cells[i].innerHTML;
        }   // m1 - End
    }
    if(window.showModalDialog){ // m1
    row.cells(0).children(0).value = invoice.toString();
    row.cells(1).children(0).value = invoice.customerNumber;
    row.cells(2).children(0).value = invoice.subscriberNumber;
    row.cells(3).children(0).value = invoice.invoiceNumber;
    row.cells(4).children(0).value = invoice.invoiceDate;
    row.cells(6).children(1).value = FormatCurr(invoice.paymentAmount);
    row.cells(6).children(1).setAttribute("oldValue", FormatCurr(invoice.paymentAmount));
    row.cells(7).children(0).value = FormatCurr(invoice.outstandingAmount);
    row.cells(8).children(0).value = FormatCurr(invoice.settleAmount);
    row.cells(8).children(0).setAttribute("oldValue", FormatCurr(invoice.settleAmount));
    }else{  // m1 - Begin
        row.cells[0].children[0].value = invoice.toString();
        row.cells[1].children[0].value = invoice.customerNumber;
        row.cells[2].children[0].value = invoice.subscriberNumber;
        row.cells[3].children[0].value = invoice.invoiceNumber;
        row.cells[4].children[0].value = invoice.invoiceDate;
        row.cells[6].children[1].value = FormatCurr(invoice.paymentAmount);
        row.cells[6].children[1].setAttribute("oldValue", FormatCurr(invoice.paymentAmount));
        row.cells[7].children[0].value = FormatCurr(invoice.outstandingAmount);
        row.cells[8].children[0].value = FormatCurr(invoice.settleAmount);
        row.cells[8].children[0].setAttribute("oldValue", FormatCurr(invoice.settleAmount));
    }   // m1 - End

    // Display charges
    if (invoice.charges.length == 0) {
        if(window.showModalDialog){ // m1
        row.cells(5).children(0).style.filter = "gray()";
        row.cells(5).children(0).disabled = true;
        }else{  // m1 - Begin
            //row.cells[5].children[0].style.filter = "gray()";
            row.cells[5].children[0].style.webkitFilter = "grayscale(100%)";
            row.cells[5].children[0].disabled = true;
        }   // m1 - End
    } else {
        if(window.showModalDialog){ // m1
        row.cells(5).children(0).filters.item("flipv").enabled = false;
        }else{  // m1 - Begin
            //row.cells[5].children[0].filters.item("flipv").enabled = false;
            isEnableCell5Filp = false;
        }   // m1 - End
        toggleInvoice(row);
    }

    // Refresh the total amount
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) + invoice.outstandingAmount
    );
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) + invoice.settleAmount
    );
}

// Toggle view of an invoice record
function toggleInvoice(row) {
    // m1 - Begin
    /*
    var invoice = invoices.item(row.cells(0).children(0).value);
    */
    var invoice;
    if(window.showModalDialog){
        invoice = invoices.item(row.cells(0).children(0).value);
    }else{
        invoice = invoices.item(row.cells[0].children[0].value);
    }
    // m1 - End
    invoice.expanded = !invoice.expanded;
    if(window.showModalDialog){ // m1
    row.cells(5).children(0).filters.item("flipv").enabled = invoice.expanded;
    }else{  // m1 - Begin
        //row.cells[5].children[0].filters.item("flipv").enabled = invoice.expanded;
        isEnableCell5Filp = invoice.expanded;
    }   // m1 - End
    if (invoice.expanded) { // Show charges
        for (var i = 0; i < invoice.charges.length; i++) {
            var charge = invoice.charges[i];
            var template = document.all.templateCharge;
            row = document.all.tblOutstanding.insertRow(row.rowIndex + 1);
            row.className = 'cell-data2';
            for (var j = 0; j < template.cells.length; j++) {
                row.insertCell(j);
                if(window.showModalDialog){ // m1
                row.cells(j).align = template.cells(j).align;
                row.cells(j).colSpan = template.cells(j).colSpan;
                row.cells(j).innerHTML = template.cells(j).innerHTML;
                }else{  // m1 - Begin
                    row.cells[j].align = template.cells[j].align;
                    row.cells[j].colSpan = template.cells[j].colSpan;
                    row.cells[j].innerHTML = template.cells[j].innerHTML;
                }   // m1 - End
            }

            if(window.showModalDialog){ // m1
            row.cells(1).children(1).style.display = "none";
            row.cells(2).children(0).value = charge.chargeType;
            row.cells(2).children(1).value = charge.description;
            row.cells(5).children(0).value = FormatCurr(charge.chargeAmount);
            row.cells(5).children(0).setAttribute("oldValue", FormatCurr(charge.chargeAmount));
            row.cells(5).children(0).readOnly = !charge.amendable;
            }else{  // m1 - Begin
                row.cells[1].children[1].style.display = "none";
                row.cells[2].children[0].value = charge.chargeType;
                row.cells[2].children[1].value = charge.description;
                row.cells[5].children[0].value = FormatCurr(charge.chargeAmount);
                row.cells[5].children[0].setAttribute("oldValue", FormatCurr(charge.chargeAmount));
                row.cells[5].children[0].readOnly = !charge.amendable;
            }   // m1 - End
        }
    } else { // Hide charges
        for (var i = invoice.charges.length; i > 0; i--) {
            document.all.tblOutstanding.deleteRow(row.rowIndex + i);
        }
    }
}


///////////////////////////////////////////////////////////////////////////////
// Collapse a customer record
function collapse(row, rowCount) {
    // m1 - Begin
    /*
    var customerNumber = row.cells(1).children(0).value;
    */
    var customerNumber;
    if(window.showModalDialog){
        customerNumber = row.cells(1).children(0).value;
    }else{
        customerNumber = row.cells[1].children[0].value;
        row.cells[4].children[0].style.webkitTransform="scaleY(1)";
    }
    // m1 - End

    if (rowCount == null && pool.exists(customerNumber)) { // if expanding
        pool.item(customerNumber).action = "collapse";
        return false;
    }

    //var customerNumber = row.cells(1).children(0).value;
    //var customer = customers.item(customerNumber);
    // m1 - Begin
    /*
    var customer = customers.item(row.cells(0).children(0).value);
    */
    var customer;
    if(window.showModalDialog){
        customer = customers.item(row.cells(0).children(0).value);
    }else{
        customer = customers.item(row.cells[0].children[0].value);
    }
    // m1 - End
    if (rowCount == null) rowCount = customer.invoices.length;

    for (var i = rowCount; i > 0; i--) {
        document.all.tblOutstanding.deleteRow(row.rowIndex + i);
    }

    return true;
}

// Remove a customer
function removeCustomer(row, rowCount) {
    //var customerNumber = row.cells(1).children(0).value;
    //var customer = customers.item(customerNumber);
    // m1 - Begin
    /*
    var customer = customers.item(row.cells(0).children(0).value);
    */
    var customer;
    if(window.showModalDialog){
        customer = customers.item(row.cells(0).children(0).value);
    }else{
        customer = customers.item(row.cells[0].children[0].value);
    }
    // m1 - End

    if (rowCount == 0) {
        if (customer.invoices.length == 0 && pool.exists(customer.customerNumber)) { // if searching
            pool.item(customer.customerNumber).action = "interrupt";
            document.all.tblOutstanding.deleteRow(row.rowIndex + 1);
        } else if (pool.exists(customer.customerNumber)) { // if expanding
            pool.item(customer.customerNumber).action = "remove";
            return false;
        } else {
            rowCount = customer.invoices.length;
        }
    }

    //customers.remove(customerNumber);
    //payments.remove(customerNumber);
    customers.remove(customer.toString());
    payments.remove(customer.toString());

    // Added by William Tam on 24 Jun 2009, for BM Security
    // remove exclude numbers in session
// Modified by Ken Cheng on 18 Aug 2016, for finance processing time
<% if(settleBean.getUser()!=null){ %>
    if(customer.customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%>){ 
        clearSessionExcludeNo(customer.customerNumber);
        removeSessionCustomer(customer.customerNumber);
    }
<% } %>
    // End of Added by William Tam on 24 Jun 2009, for BM Security
// End of modified by Ken Cheng on 18 Aug 2016, for finance processing time
    // Refresh the total amount
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) - customer.outstandingAmount
    );
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) - customer.settleAmount
    );

    // Remove rows
    if (customer.expanded) {
        for (var i = rowCount; i >= 1; i--) {
            document.all.tblOutstanding.deleteRow(row.rowIndex + i);
        }
    }
    document.all.tblOutstanding.deleteRow(row.rowIndex);

    //Added by Billy Pang on 6/6/2017 for adding Kiosk OS (remove row case)
    document.all.kioskCustAmount.value = "0.0";
    document.all.kioskSubrAmount.value = "0.0";
    //End Added by Billy Pang on 6/6/2017 for adding Kiosk OS (remove row case)
        
    return false;
}

// Remove a prepaid invoice
function removeInvoice(row) {
    //var invoiceNumber = row.cells(3).children(0).value;
    //var invoice = invoices.item(invoiceNumber);
    //invoices.remove(invoiceNumber);
    //payments.remove(invoiceNumber);
    // m1 - Begin
    /*
    var key = row.cells(0).children(0).value;
    */
    var key;
    if(window.showModalDialog){
        key = row.cells(0).children(0).value;
    }else{
        key = row.cells[0].children[0].value;
    }
    // m1 - End
    var invoice = invoices.item(key);
    invoices.remove(key);
    payments.remove(key);

    // Refresh the total amount
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) - invoice.outstandingAmount
    );
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) - invoice.settleAmount
    );

    // Remove rows
    if (invoice.expanded) {
        for (var i = 0; i < invoice.charges.length; i++) {
            document.all.tblOutstanding.deleteRow(row.rowIndex + 1);
        }
    }
    document.all.tblOutstanding.deleteRow(row.rowIndex);

    return false;
}

// Remove an error row
function removeError(rowIndex) {
    document.all.tblOutstanding.deleteRow(rowIndex);
    return false;
}



// Create a payment object
function createPaymentObject(itemNumber, outstandingAmount, paymentAmount, settleAmount, allowAdvancedPayment) {
    var payment = new Payment();
    payment.outstandingAmount = outstandingAmount;
    payment.paymentAmount = paymentAmount;
    payment.settleAmount = settleAmount;
    payment.allowAdvancedPayment = allowAdvancedPayment;
    return payment;
}


///////////////////////////////////////////////////////////////////////////////
// Set the subscriber number for printing on receipt
//function setSubscriberForPrint(customerNumber, subscriberNumber) {
function setSubscriberForPrint(key, subscriberNumber) {
    //customers.item(customerNumber).subscriberNumber = subscriberNumber;
    customers.item(key).subscriberNumber = subscriberNumber;
}

///////////////////////////////////////////////////////////////////////////////
// View bill / ledger
//Modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix
//  Root cause: default window.open keeps an opener back-reference + childWins[]
//  grows unbounded with stale cross-process Window proxies. After iPadOS 26.4/26.5
//  tightened site isolation / WebContent Process discard, this combination caused
//  the parent renderer to be killed -> "this page can't be opened".
//  Fix:
//    1) Open with noopener,noreferrer   -> break opener link.
//    2) Reuse a single _gnvLedgerWin    -> no zombie window stacking.
//    3) _pruneChildWins() each call     -> stop unbounded stale-proxy growth.
//    4) try/catch every cross-window call -> swallow cross-process SecurityError.
//  Verified: LedMainGNV.jsp does NOT use window.opener, so noopener is safe.
//function viewLedger(customerNumber) {
//    //window.open("/servlet/LedMain?customerNumber=" + customerNumber, "billWindow", "toolbar=no,width=780,height=560,left=0,top=0");
//    // Modified by William Tam on 16 May 2013, for WebLogic Migration
//    //window.open("../jsp/fes/cs/LedMainGNV.jsp?customerNumber=" + customerNumber, "billWindow", "toolbar=no,width=780,height=560,left=0,top=0");
//    var gnvWin = window.open("/jsp/fes/cs/ledger/LedMainGNV.jsp?customerNumber=" + customerNumber, "billWindow", "toolbar=no,width=780,height=560,left=0,top=0");   // m1
//    // End of Modified by William Tam on 16 May 2013, for WebLogic Migration
//    childWins.push(gnvWin); // m1
//}
var _gnvLedgerWin = null;
function _pruneChildWins() {
    try {
        if (typeof childWins === "undefined" || !childWins) return;
        for (var i = childWins.length - 1; i >= 0; i--) {
            try { if (!childWins[i] || childWins[i].closed) childWins.splice(i, 1); }
            catch (e) { childWins.splice(i, 1); } // cross-process / revoked proxy
        }
    } catch (e) { /* ignore */ }
}
function viewLedger(customerNumber) {
    _pruneChildWins();
    var url = "/jsp/fes/cs/ledger/LedMainGNV.jsp?customerNumber=" + customerNumber;
    // Reuse existing ledger window if it's still open (avoid creating zombies).
    try {
        if (_gnvLedgerWin && !_gnvLedgerWin.closed) {
            try { _gnvLedgerWin.location.replace(url); } catch (e) { _gnvLedgerWin = null; }
            if (_gnvLedgerWin) { try { _gnvLedgerWin.focus(); } catch (e) {} return; }
        }
    } catch (e) { _gnvLedgerWin = null; }
    var features = "noopener,noreferrer,toolbar=no,width=780,height=560,left=0,top=0";
    var gnvWin = window.open(url, "_blank", features);
    // With noopener, window.open() returns null in modern browsers; don't push null.
    if (gnvWin) {
        _gnvLedgerWin = gnvWin;
        try { childWins.push(gnvWin); } catch (e) {}
    }
}
//End modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix

// View payment details
function viewPayment(row) {
    //var customerNumber = row.cells(1).children(0).value;
    //var itemNumber = (specialCustomer.indexOf(customerNumber) < 0 ? customerNumber : row.cells(3).children(0).value);
    //showPayment(itemNumber);
    if(window.showModalDialog){ // m1
    showPayment(row.cells(0).children(0).value);
    }else{  // m1 - Begin
        showPayment(row.cells[0].children[0].value);
    }   // m1 - End
}

// m1 - Begin
var editPaymentWin;
var editPaymentInterval;
var tmpItemNum;
// m1 - End
function showPayment(itemNumber) {
    tmpItemNum = itemNumber;    // m1
    if(window.showModalDialog){ // m1
    //Modified by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
    //showModalDialog("/servlet/fes.sa.settle.SASettleSearchServlet?action=editPayment&itemNumber=" + itemNumber, payments, "dialogWidth:600px; dialogHeight:550px; dialogTop:50px; dialogLeft: 100px;");
    showModalDialog("/servlet/fes.sa.settle.SASettleSearchServlet?action=editPayment&itemNumber=" + itemNumber, payments, "dialogWidth:600px; dialogHeight:580px; dialogTop:50px; dialogLeft: 100px;");
    afterEditPayment();
    //End Modified by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
    }else{  // m1 - Begin
        editPaymentWin = window.open("/servlet/fes.sa.settle.SASettleSearchServlet?action=editPayment&itemNumber=" + itemNumber, "_editPayment", "width=600px,height=580px");
        childWins.push(editPaymentWin);
        editPaymentInterval = setInterval(function(){
            if(editPaymentWin == null || (editPaymentWin != null && editPaymentWin.closed)){
                afterEditPayment();
                clearInterval(editPaymentInterval);
            }
        }, 100);
    }
    /*
    for (var i = 0; i < document.all.paymentMethod.length - 2; i++) {
        // Synchronize item record with payment record
        row = paymentMethod[i].parentElement.parentElement;
        var payment = payments.item(row.cells(0).children(0).value);
        //var payment;
        //if (specialCustomer.indexOf(row.cells(1).children(0).value) < 0) {
        if (customers.exists(itemNumber)) {
            //payment = payments.item(row.cells(1).children(0).value);
            //customers.item(row.cells(1).children(0).value).paymentAmount = payment.paymentAmount;
            customers.item(itemNumber).paymentAmount = payment.paymentAmount;
        // Modified by William Tam on 9 Jan 2009, bug fix
        //} else {
        } else if (invoices.exists(itemNumber)) {
        // End of Modified by William Tam on 9 Jan 2009, bug fix
            //payment = payments.item(row.cells(3).children(0).value);
            //invoices.item(row.cells(3).children(0).value).paymentAmount = payment.paymentAmount;
            //invoices.item(row.cells(3).children(0).value).settleAmount = payment.paymentAmount;
            invoices.item(itemNumber).paymentAmount = payment.paymentAmount;
            invoices.item(itemNumber).settleAmount = payment.paymentAmount;
        }

        // Compact payment methods
        for (var j = payment.method.length - 1; j >= 0; j--) {
            var method = payment.method[j];
            if (method.code.trim() == "") {
                for (var k = j; k < payment.method.length - 1; k++) {
                    payment.method[k] = payment.method[k + 1];
                }
                payment.method.length--;
            }
        }

        // Render shortcut payment code and amount textboxes differently according to
        // the number of payment methods specified
        if (payment.method.length > 1) {
            document.all.paymentMethod[i].value = "*";
            document.all.paymentAmount[i].className = "textbox-read";
            document.all.paymentAmount[i].readOnly = true;
        } else if (payment.method.length == 1) {
            document.all.paymentMethod[i].value = payment.method[0].code;
            document.all.paymentAmount[i].className = "textbox";
            document.all.paymentAmount[i].readOnly = false;
        } else {
            document.all.paymentMethod[i].value = "";
            document.all.paymentAmount[i].className = "textbox";
            document.all.paymentAmount[i].readOnly = false;
        }

        document.all.paymentAmount[i].value = FormatCurr(payment.paymentAmount);
        document.all.paymentMethod[i].setAttribute("oldValue", document.all.paymentMethod[i].value);
        document.all.paymentAmount[i].setAttribute("oldValue", document.all.paymentAmount[i].value);
        document.all.settleSubtotal[i].value = FormatCurr(payment.settleAmount);
        document.all.settleSubtotal[i].setAttribute("oldValue", document.all.settleSubtotal[i].value);
    }
    */
    // m1 - End
}

function afterEditPayment(){    // m1
    var paymentMethod = document.all.paymentMethod; // m1
    var itemNumber = tmpItemNum;    // m1
    for (var i = 0; i < document.all.paymentMethod.length - 2; i++) {
        // Synchronize item record with payment record
        row = paymentMethod[i].parentElement.parentElement;
        // m1 - Begin
        /*
        var payment = payments.item(row.cells(0).children(0).value);
        */
        var payment;
        if(window.showModalDialog){
            payment = payments.item(row.cells(0).children(0).value);
        }else{
            payment = payments.item(row.cells[0].children[0].value);
        }
        // m1 - End
        //var payment;
        //if (specialCustomer.indexOf(row.cells(1).children(0).value) < 0) {
        if (customers.exists(itemNumber)) {
            //payment = payments.item(row.cells(1).children(0).value);
            //customers.item(row.cells(1).children(0).value).paymentAmount = payment.paymentAmount;
            customers.item(itemNumber).paymentAmount = payment.paymentAmount;
        // Modified by William Tam on 9 Jan 2009, bug fix
        //} else {
        } else if (invoices.exists(itemNumber)) {
        // End of Modified by William Tam on 9 Jan 2009, bug fix
            //payment = payments.item(row.cells(3).children(0).value);
            //invoices.item(row.cells(3).children(0).value).paymentAmount = payment.paymentAmount;
            //invoices.item(row.cells(3).children(0).value).settleAmount = payment.paymentAmount;
            invoices.item(itemNumber).paymentAmount = payment.paymentAmount;
            invoices.item(itemNumber).settleAmount = payment.paymentAmount;
        }

        // Compact payment methods
        for (var j = payment.method.length - 1; j >= 0; j--) {
            var method = payment.method[j];
            if (method.code.trim() == "") {
                for (var k = j; k < payment.method.length - 1; k++) {
                    payment.method[k] = payment.method[k + 1];
                }
                payment.method.length--;
            }
        }

        // Render shortcut payment code and amount textboxes differently according to
        // the number of payment methods specified
        if (payment.method.length > 1) {
            document.all.paymentMethod[i].value = "*";
            document.all.paymentAmount[i].className = "textbox-read";
            document.all.paymentAmount[i].readOnly = true;
        } else if (payment.method.length == 1) {
            document.all.paymentMethod[i].value = payment.method[0].code;
            document.all.paymentAmount[i].className = "textbox";
            document.all.paymentAmount[i].readOnly = false;
        } else {
            document.all.paymentMethod[i].value = "";
            document.all.paymentAmount[i].className = "textbox";
            document.all.paymentAmount[i].readOnly = false;
        }

        document.all.paymentAmount[i].value = FormatCurr(payment.paymentAmount);
        document.all.paymentMethod[i].setAttribute("oldValue", document.all.paymentMethod[i].value);
        document.all.paymentAmount[i].setAttribute("oldValue", document.all.paymentAmount[i].value);
        document.all.settleSubtotal[i].value = FormatCurr(payment.settleAmount);
        document.all.settleSubtotal[i].setAttribute("oldValue", document.all.settleSubtotal[i].value);
    }
}   // m1

var tmpExcNumRow;   // m1
// Added by William Tam on 24 Jun 2009, for BM Security
function searchExcludedNumber(row) {
    // m1 - Begin
    /*
    var customer = customers.item(row.cells(0).children(0).value);
    */
    var customer;
    if(window.showModalDialog){
        customer = customers.item(row.cells(0).children(0).value);
    }else{
        customer = customers.item(row.cells[0].children[0].value);
    }
    tmpCustomer = customer;
    tmpExcNumRow = row;
    if(window.showModalDialog){ // m1 - End
    var result = window.showModalDialog("/jsp/fes/sa/settle/SASettleExcludePage.jsp",
                      {customer: customer},   
                     "dialogTop:200px; dialogLeft:150px; dialogWidth:400px; dialogHeight:130px");
        handleSettleExclude(result);    // m1 - Begin
    }else{
        var result = window.open("/jsp/fes/sa/settle/SASettleExcludePage.jsp", "_setlExclude", "width=500px,height=170px");
        childWins.push(result);
    }
    /*
    if(result != null){
        var invoice = result.invoice;
        var exInvGroup = result.excludeInvoiceGroup;
        var searchNo = result.searchNo;
        addExcludeNumber(row, customer, invoice, exInvGroup, searchNo);
    }
    */
    // m1 - End
}

function handleSettleExclude(result){   // m1
    if(result != null){
        var invoice = result.invoice;
        var exInvGroup = result.excludeInvoiceGroup;
        var searchNo = result.searchNo;
        addExcludeNumber(tmpExcNumRow, tmpCustomer, invoice, exInvGroup, searchNo); // m1
    }
}   // m1

function addExcludeNumber(customerRow, customer, invoice, exInvGroup, searchNo){
    var tblOutstanding = document.all.tblOutstanding;

    // update javascript object
    customer.invoices[customer.invoices.length++] = invoice;
    var newRow = tblOutstanding.insertRow(customerRow.rowIndex+customer.invoices.length);
    
    // Refresh the subtotal fields (Settle and OS amount)
    var customerNumber = customer.customerNumber;
    var payment = payments.item(customerNumber);
    var delSettleAmount = 0;
    
    // update outstanding and settle amount of each row
    for(var i = 0; i < customer.invoices.length; i++){
        for(var j=0; j < exInvGroup.length; j++){
            if(exInvGroup[j].invoiceType == customer.invoices[i].invoiceType &&
               (exInvGroup[j].invoiceType == "O" || exInvGroup[j].invoiceType == "W" )) {
                customer.invoices[i].outstandingAmount = new Number( customer.invoices[i].outstandingAmount) - new Number(exInvGroup[j].outstandingAmount);
                // remove any added handset invoice(s)
                var removedInvoices = new Array();
                var handsetInvoices = cloneArray(customer.invoices[i].addedInvoices);
                for(var n=handsetInvoices.length-1; n >=0; n--){
                    if(handsetInvoices[n].invoiceNumber == searchNo ||
                       handsetInvoices[n].subscriberNumber == searchNo){
                        removedInvoices.push(handsetInvoices.splice(n,1)[0]);
                    }
                }
                customer.invoices[i].addedInvoices = handsetInvoices;
                var removedInvoicesTotal = calInvoicesTotal(removedInvoices);
                customer.settleAmount = new Number(customer.settleAmount) - removedInvoicesTotal;
                //payment.paymentAmount = new Number(payment.paymentAmount) - removedInvoicesTotal;
                delSettleAmount = new Number(delSettleAmount) + removedInvoicesTotal;
                exInvGroup[j].settleAmount = 0;
                customer.invoices[i].settleAmount = new Number( customer.invoices[i].settleAmount) - removedInvoicesTotal;
            } 
            else if(exInvGroup[j].invoiceType == customer.invoices[i].invoiceType && 
            exInvGroup[j].invoiceDate == customer.invoices[i].invoiceDate
            ){
                customer.invoices[i].outstandingAmount = new Number( customer.invoices[i].outstandingAmount) - new Number(exInvGroup[j].outstandingAmount);
                if( customer.invoices[i].settleAmount - exInvGroup[j].outstandingAmount > 0  ){
                    customer.settleAmount = new Number(customer.settleAmount) -  new Number(exInvGroup[j].outstandingAmount);
                    payment.paymentAmount = Math.max(new Number(payment.paymentAmount) - new Number(exInvGroup[j].outstandingAmount),0);
                    delSettleAmount = new Number(delSettleAmount) + new Number(exInvGroup[j].outstandingAmount);
                    exInvGroup[j].settleAmount = exInvGroup[j].outstandingAmount;
                    customer.invoices[i].settleAmount = new Number( customer.invoices[i].settleAmount) - new Number(exInvGroup[j].outstandingAmount);
                } else if (customer.invoices[i].settleAmount > 0) {
                    customer.settleAmount = new Number(customer.settleAmount) -  new Number(customer.invoices[i].settleAmount);
                    payment.paymentAmount = Math.max(new Number(payment.paymentAmount) - new Number(customer.invoices[i].settleAmount), 0);
                    delSettleAmount = new Number(delSettleAmount) + new Number(customer.invoices[i].settleAmount)
                    exInvGroup[j].settleAmount = customer.invoices[i].settleAmount;
                    customer.invoices[i].settleAmount = 0;
                }
                break;
            }
        }
    }
    customer.outstandingAmount = new Number(customer.outstandingAmount) + new Number(invoice.outstandingAmount);
    payment.outstandingAmount = customer.outstandingAmount;
    payment.settleAmount = customer.settleAmount;
    
    // Added to exclude List
    if(exInvGroup != null){
        excludeInvoiceGroups.add(searchNo, exInvGroup);
    }
    
    /*
    customer.settleAmount =
        new Number(customerRow.cells(7).children(0).value) + new Number(invoice.settleAmount);
    customer.outstandingAmount = new Number(customer.outstandingAmount) + new Number(invoice.outstandingAmount);
    payment.paymentAmount = new Number(payment.paymentAmount) + new Number(invoice.settleAmount);
    payment.outstandingAmount = customer.outstandingAmount;
    payment.settleAmount = customer.settleAmount;
    */
    if(window.showModalDialog){ // m1
    customerRow.cells(5).children(1).value = FormatCurr(payment.paymentAmount);
    customerRow.cells(5).children(1).setAttribute("oldValue", FormatCurr(payment.paymentAmount));
    customerRow.cells(6).children(0).value = FormatCurr(customer.outstandingAmount);
    customerRow.cells(7).children(0).value = FormatCurr(customer.settleAmount);
    customerRow.cells(7).children(0).setAttribute("oldValue", FormatCurr(customer.settleAmount));
    }else{  // m1 - Begin
        customerRow.cells[5].children[1].value = FormatCurr(payment.paymentAmount);
        customerRow.cells[5].children[1].setAttribute("oldValue", FormatCurr(payment.paymentAmount));
        customerRow.cells[6].children[0].value = FormatCurr(customer.outstandingAmount);
        customerRow.cells[7].children[0].value = FormatCurr(customer.settleAmount);
        customerRow.cells[7].children[0].setAttribute("oldValue", FormatCurr(customer.settleAmount));
    }   // m1 - End

    // Refresh the total field
    /*
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) +
        new Number(invoice.settleAmount)
    );    
    */
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) -
        new Number(delSettleAmount)
    );
    
    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) + 
        new Number(invoice.outstandingAmount)
    );

    payment.askAdvancedPayment = (payment.paymentAmount > payment.settleAmount);

    // update page
    collapse(customerRow);
    expand(customerRow);
}

function removeExcludeNo(customerRow, row, invoice){
    // remove invoice in session
    removeSessionExcludeNo(invoice);

    // update javascript objects
    var customer = customers.item(invoice.customerNumber);
    var removedItem = customer.invoices.splice(row.rowIndex - customerRow.rowIndex - 1, 1);

    // update screen
    var tblOutstanding = document.all.tblOutstanding;
    tblOutstanding.deleteRow(row.rowIndex);
    
    // Refresh the subtotal fields (Settle and OS amount)
    var customerNumber = customer.customerNumber;
    var payment = payments.item(customerNumber);
    var exInvGroup = null;
    var addSettleAmount = 0;
    var searchNo = "";
    if(excludeInvoiceGroups.exists(invoice.subscriberNumber)){
        searchNo = invoice.subscriberNumber;
        exInvGroup = excludeInvoiceGroups.item(invoice.subscriberNumber);
    } else {
        searchNo = invoice.invoiceNumber;
        exInvGroup = excludeInvoiceGroups.item(invoice.invoiceNumber);
    }
    
    // update outstanding and settle amount of each row
    for(var i = 0; i < customer.invoices.length; i++){
        for(var j=0; j < exInvGroup.length; j++){
            if(exInvGroup[j].invoiceType == customer.invoices[i].invoiceType && 
            exInvGroup[j].invoiceDate == customer.invoices[i].invoiceDate
            ){
                customer.invoices[i].outstandingAmount = new Number( customer.invoices[i].outstandingAmount) + new Number(exInvGroup[j].outstandingAmount);
                if( exInvGroup[j].settleAmount > 0 && new Number(exInvGroup[j].settleAmount) + new Number(customer.invoices[i].settleAmount) < customer.invoices[i].outstandingAmount){
                    customer.settleAmount = new Number(customer.settleAmount) + new Number(exInvGroup[j].settleAmount);
                    payment.paymentAmount = new Number(payment.paymentAmount) + new Number(exInvGroup[j].settleAmount);
                    addSettleAmount = new Number(addSettleAmount) + new Number(exInvGroup[j].settleAmount);
                    customer.invoices[i].settleAmount = new Number( customer.invoices[i].settleAmount) + new Number(exInvGroup[j].settleAmount);
                } else if ( exInvGroup[j].settleAmount > 0){
                    customer.settleAmount = new Number(customer.settleAmount) + new Number(customer.invoices[i].outstandingAmount) - new Number(customer.invoices[i].settleAmount);
                    payment.paymentAmount = new Number(payment.paymentAmount) + new Number(customer.invoices[i].outstandingAmount) - new Number(customer.invoices[i].settleAmount);
                    addSettleAmount = new Number(addSettleAmount) + new Number(customer.invoices[i].outstandingAmount) - new Number(customer.invoices[i].settleAmount);
                    customer.invoices[i].settleAmount = customer.invoices[i].outstandingAmount;
                }
                break;
            }
        }
    }
    customer.outstandingAmount = new Number(customer.outstandingAmount) - new Number(invoice.outstandingAmount);
    payment.outstandingAmount = customer.outstandingAmount;
    payment.settleAmount = customer.settleAmount;

    // remove from exclude List
    if(exInvGroup != null){
        excludeInvoiceGroups.remove(searchNo);
    }

    /*
    customer.settleAmount =
        new Number(customerRow.cells(7).children(0).value) - new Number(invoice.settleAmount);
    customer.outstandingAmount = new Number(customer.outstandingAmount) - new Number(invoice.outstandingAmount);
    payment.paymentAmount = new Number(payment.paymentAmount) - new Number(invoice.settleAmount);
    payment.outstandingAmount = customer.outstandingAmount;
    payment.settleAmount = customer.settleAmount;
    */
    if(window.showModalDialog){ // m1
    customerRow.cells(5).children(1).value = FormatCurr(payment.paymentAmount);
    customerRow.cells(5).children(1).setAttribute("oldValue", FormatCurr(payment.paymentAmount));
    customerRow.cells(6).children(0).value = FormatCurr(customer.outstandingAmount);
    customerRow.cells(7).children(0).value = FormatCurr(customer.settleAmount);
    customerRow.cells(7).children(0).setAttribute("oldValue", FormatCurr(customer.settleAmount));
    }else{  // m1 - Begin
        customerRow.cells[5].children[1].value = FormatCurr(payment.paymentAmount);
        customerRow.cells[5].children[1].setAttribute("oldValue", FormatCurr(payment.paymentAmount));
        customerRow.cells[6].children[0].value = FormatCurr(customer.outstandingAmount);
        customerRow.cells[7].children[0].value = FormatCurr(customer.settleAmount);
        customerRow.cells[7].children[0].setAttribute("oldValue", FormatCurr(customer.settleAmount));
    }   // m1 - End

    // Refresh the total field
    /*
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) - 
        new Number(invoice.settleAmount)
    );
    */
    document.all.settleTotal.value = FormatCurr(
        new Number(document.all.settleTotal.value) +
        new Number(addSettleAmount)
    );

    document.all.outstandingTotal.value = FormatCurr(
        new Number(document.all.outstandingTotal.value) -
        new Number(invoice.outstandingAmount)
    );

    payment.askAdvancedPayment = (payment.paymentAmount > payment.settleAmount);
    
    // update page
    collapse(customerRow);
    expand(customerRow);
}

function removeSessionExcludeNo(invoice) {
    var content = {
        action:             (invoice.invoiceNumber=="-"?"removeExcludeCellular":"removeExcludeInvoiceNo"),
        invoiceNo:          invoice.invoiceNumber,
        cellular:           invoice.subscriberNumber,
        customerNumber:     invoice.customerNumber
    };
    
    dojo.io.bind({
        url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
        method:  "get",
        content: content,
        error: function(type, data, evt) {
            alert("Server error.");
        },
        load: function(type, result, evt) {},
            mimetype: "text/json",
            sync: true
        }
    );
}

function clearSessionExcludeNo(customerNumber) {
    var content = {
        action:             "clearExcludeNo",
        customerNumber:     customerNumber
    };
    
    dojo.io.bind({
        url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
        method:  "get",
        content: content,
        error: function(type, data, evt) {
            alert("Server error.");
        },
        load: function(type, result, evt) {},
            mimetype: "text/json",
            sync: true
        }
    );
}

function removeSessionCustomer(customerNumber) {
    var content = {
        action:             "removeCustomer",
        customerNumber:     customerNumber
    };
    
    dojo.io.bind({
        url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
        method:  "get",
        content: content,
        error: function(type, data, evt) {
            alert("Server error.");
        },
        load: function(type, result, evt) {},
            mimetype: "text/json",
            sync: true
        }
    );
}

// m1 - Begin
var tmpInv;
var tmpSetlField;
// m1 - End
function showAllHandsetInvoice(invoice, settleField) {
    // m1 - Begin
    tmpInv = invoice;
    tmpSetlField = settleField;
    if(window.showModalDialog){ // m1 - End
     var resultInvoices = window.showModalDialog("/jsp/fes/sa/settle/SASettleInvoiceList.jsp",
                            {
                                invoiceType: invoice.invoiceType,
                                customerNumber: invoice.customerNumber,
                                addedInvoices: invoice.addedInvoices
                            },
                            "dialogTop:150px; dialogLeft:150px; dialogWidth:600px; dialogHeight:430px");
        handleSettleInvoiceList(resultInvoices);    // m1 - Begin
    }else{
        var resultInvoices = window.open("/jsp/fes/sa/settle/SASettleInvoiceList.jsp", "_setlInvList", "width=600px,height=430px");
        childWins.push(resultInvoices);
    }
    /*
    if(resultInvoices != null){
        var totalSettleAmount = calInvoicesTotal(resultInvoices);
        invoice.addedInvoices = resultInvoices;
        if( settleField.value != totalSettleAmount){        
            settleField.value = totalSettleAmount;
            settleField.onblur();
        }
    }
    */
    // m1 - End
}

function handleSettleInvoiceList(resultInvoices){   // m1
    if(resultInvoices != null){
        var totalSettleAmount = calInvoicesTotal(resultInvoices);
        tmpInv.addedInvoices = resultInvoices;  // m1 - Begin
        if( tmpSetlField.value != totalSettleAmount){        
            tmpSetlField.value = totalSettleAmount;
            tmpSetlField.onblur();
        }
    }
}   // m1 - End

function promptAcctMgr(customer) {
    var content = {
        action:             "promptAcctMgr",
        custNum:            customer.customerNumber
    };
    
    dojo.io.bind({
        url:     "/servlet/fes.sa.settle.SASettleSearchServlet",
        method:  "get",
        content: content,
        error: function(type, data, evt) {
            alert("Server error.");
        },
        load: function(type, result, evt) {
            if(result){
                if(result.message){
                    alert(result.message);
                }
            }
        },
        mimetype: "text/json",
        sync: true
        }
    );
}

// End of added by William Tam on 24 Jun 2009, for BM Security


///////////////////////////////////////////////////////////////////////////////
// Save
function save() {
    if (saveStatus == "processing") {
        return;
    }
    saveStatus = "processing";
    
    // Validate in client side
    var settleDetected = false;
    var keys = payments.keys();
    
    var i = 0;
    for (i = 0; i < keys.length; i++) {
        var payment = payments.item(keys[i]);

        if (payment.settled || payment.paymentAmount == 0) continue;
        settleDetected = true;

        if (payment.method.length == 0) {
            alert("[" + keys[i] + "] <%=messageBundle.getString("MissingPaymentMethod")%>");
            break;
        }

        if (invoices.exists(keys[i]) && payment.paymentAmount < invoices.item(keys[i]).chargeAmount) {
            alert("[" + keys[i] + "] <%=messageBundle.getString("PaymentLessThanCharge")%>");
            break;
        }

        if (payment.paymentAmount < payment.settleAmount) {
            alert("[" + keys[i] + "] <%=messageBundle.getString("PaymentLessThanSettle")%>");
            break;
        }

        for (j = 0; j < payment.method.length; j++) {
            if (payment.method[j].paymentAmount <= 0) {
                alert("[" + keys[i] + "] <%=messageBundle.getString("InvalidPaymentAmount")%>");
                break;
            }
        }

        // Added by William Tam on 24 Jun 2009, for BM Security
// Modified by Ken Cheng on 18 Aug 2016, for finance processing time
        <% if(settleBean.getUser()!=null){ %>
        if(customers.item(keys[i])){
        if(customers.item(keys[i]).customerType == "P" && <%=(settleBean.getUser().hasRight("C44")||settleBean.getUser().hasRight("C46"))%> && customers.item(keys[i]).startRequest == <%=SASettleConstant.SEARCH_CUST_ADDED%>){
            var invoiceTotal = calInvoicesTotal(customers.item(keys[i]).invoices);
            if(customers.item(keys[i]).settleAmount > invoiceTotal) {
                if(!confirm("[" + keys[i] + "] Advanced payment amount $"+FormatCurr(customers.item(keys[i]).settleAmount - invoiceTotal)+" with charge type 01 will be generated? Otherwise please settle with invoice(s).")){
                    saveStatus = "idle";
                    return;
                } else {
                    // update page amount
                    var tblOutstanding = document.all.tblOutstanding;
                    var rowIndex = 2;
                    while (rowIndex < tblOutstanding.rows.length) {
                        if(window.showModalDialog){ // m1
                        if(tblOutstanding.rows(rowIndex).cells(1).children(0).type == "text"
                        && tblOutstanding.rows(rowIndex).cells(1).children(0).value == keys[i]){ 
                            tblOutstanding.rows(rowIndex).cells(tblOutstanding.rows(rowIndex).cells.length - 1).children(0).value = invoiceTotal;
                            tblOutstanding.rows(rowIndex).cells(tblOutstanding.rows(rowIndex).cells.length - 1).children(0).onblur();
                            break;
                        }
                        }else{  // m1 - Begin
                            if(tblOutstanding.rows[rowIndex].cells[1].children[0].type == "text"
                            && tblOutstanding.rows[rowIndex].cells[1].children[0].value == keys[i]){ 
                                tblOutstanding.rows[rowIndex].cells[tblOutstanding.rows[rowIndex].cells.length - 1].children[0].value = invoiceTotal;
                                tblOutstanding.rows[rowIndex].cells[tblOutstanding.rows[rowIndex].cells.length - 1].children[0].onblur();
                                break;
                            }
                        }   // m1 - End
                        rowIndex++;
                    }
                    payment.askAdvancedPayment = false; // prevent doubly asking question
                }
            }
        }
        }
        <% } %>
        // End of Added by William Tam on 24 Jun 2009, for BM Security
// End of modified by Ken Cheng on 18 Aug 2016, for finance processing time
        if (payment.askAdvancedPayment) {
            if (confirm("[" + keys[i] + "] <%=MessageFormat.format(messageBundle.getString("AskAdvancedPayment"), new Object[] {"\" + FormatCurr(payment.paymentAmount - payment.settleAmount) + \"", "\" + customers.item(keys[i]).defaultChargeType + \""})%>")) {
                payment.askAdvancedPayment = false;
            } else {
                saveStatus = "idle";
                return;
            }
        }
    }

    // Problem with the just checked item
    if (i < keys.length) {
        showPayment(keys[i]);
        saveStatus = "idle";
        return;
    }

    // Nothing is settled
    if (!settleDetected) {
        alert("<%=messageBundle.getString("SettlementNoCaseSelected")%>");
        saveStatus = "idle";
        return;
    }

    // Collapse rows
    var tblOutstanding = document.all.tblOutstanding;
    var rowIndex = 2;
    while (rowIndex < tblOutstanding.rows.length) {
        // m1 - Begin
        /* var row = tblOutstanding.rows(rowIndex);*/
        var row;
        if(window.showModalDialog){
            row = tblOutstanding.rows(rowIndex);
        }else{
            row = tblOutstanding.rows[rowIndex];
        }
        // m1 - End
        //var itemNumber = row.cells(1).children(0).value;
        // m1 - Begin
        var tmpEle;
        if(window.showModalDialog){
            tmpEle = row.cells(0);
        }else{
            tmpEle = row.cells[0];
        }
        //if (row.cells(0).children.length == 0) {
        if (tmpEle.children.length == 0) {
        // m1 - End
            tblOutstanding.deleteRow(rowIndex);
        } else {
            // m1 - Begin
            /*var itemNumber = row.cells(0).children(0).value;*/
            var itemNumber;
            if(window.showModalDialog){
                itemNumber = tmpEle.children(0).value;
            }else{
                itemNumber = tmpEle.children[0].value;
            }
            // m1 - End
            if (pool.exists(itemNumber)) { // if expanding
                pool.item(itemNumber).action = "interrupt";
            }

        //if (itemNumber == "") {
        //    tblOutstanding.deleteRow(rowIndex);
        //} else if (specialCustomer.indexOf(itemNumber) >= 0) {
        //    rowIndex++;
        //} else {
            if (customers.item(itemNumber) != null && customers.item(itemNumber).expanded) {
                customers.item(itemNumber).expanded = false;
                if(window.showModalDialog){ // m1
                row.cells(4).children(0).filters.item("flipv").enabled = false;
                }else{  // m1 - Begin
                    //row.cells[4].children[0].filters.item("flipv").enabled = false;
                    isEnableCell4Filp = false;
                }   // m1 - End
            } else if (invoices.item(itemNumber) != null && invoices.item(itemNumber).expanded) {
                invoices.item(itemNumber).expanded = false;
                if(window.showModalDialog){ // m1
                row.cells(5).children(0).filters.item("flipv").enabled = false;
                }else{  // m1 - Begin
                    //row.cells[5].children[0].filters.item("flipv").enabled = false;
                    isEnableCell5Filp = false;
                }   // m1 - End
            }
            rowIndex++;
        }
    }

    // Set request parameters
    var frmSave = document.all.frmSave;
    frmSave.innerHTML = "";
    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=printer VALUE=\"" + document.all.printers.value + "\">");

    // Post to server side
    keys = payments.keys();
    for (var i = 0; i < keys.length; i++) {
        var payment = payments.item(keys[i]);
        var method = payment.method;
        var chargeItemCount = 0;

        //frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=customerNumber VALUE=\"" + (keys[i].charAt(0) != "I" ? keys[i] : "66666666") + "\">");
        if (customers.item(keys[i]) != null) {
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=customerNumber VALUE=\"" + customers.item(keys[i]).customerNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=subscriberNumberForPrint VALUE=\"" + customers.item(keys[i]).subscriberNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=paymentAmount VALUE=\"" + FormatCurr(payment.paymentAmount) + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=settleAmount VALUE=\"" + FormatCurr(payment.settleAmount) + "\">");
            // Added by William Tam on 24 Jun 2009, for BM Security
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=customerType VALUE=\"" + customers.item(keys[i]).customerType + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=startRequest VALUE=\"" + customers.item(keys[i]).startRequest + "\">");
            // End of added by William Tam on 24 Jun 2009, for BM Security

            var custInvoices = customers.item(keys[i]).invoices;
            var settleCount = 0;
            //var interimAmount = 0;
            // Added by William Tam on 24 Jun 2009, for BM Security
            var excludeInvoiceCount = 0;
            // End of added by William Tam on 24 Jun 2009, for BM Security

            for (var j = 0; j < custInvoices.length; j++) {
                // Modified by William Tam on 24 Jun 2009, for BM Security
                //if (custInvoices[j].settleAmount > 0) {
                if (custInvoices[j].settleAmount > 0 || custInvoices[j].invoiceType == "E") {
                    var hasAddedInvoices = false;
                    for(var n=0; n < custInvoices[j].addedInvoices.length; n++){
                        hasAddedInvoices = true;
                        settleCount++;
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=subscriberNumber VALUE=\"" + custInvoices[j].addedInvoices[n].subscriberNumber + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceNumber VALUE=\"" + custInvoices[j].addedInvoices[n].invoiceNumber + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSystemInd VALUE=\"" + custInvoices[j].addedInvoices[n].systemInd + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceChargeType VALUE=\"" + custInvoices[j].addedInvoices[n].chargeType + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSettleAmount VALUE=\"" + FormatCurr(custInvoices[j].addedInvoices[n].settleAmount) + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceType VALUE=\"" + custInvoices[j].addedInvoices[n].invoiceType + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceDate VALUE=\"" + custInvoices[j].addedInvoices[n].invoiceDate + "\">");
                        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceOSAmount VALUE=\"" + custInvoices[j].addedInvoices[n].outstandAmount + "\">");
                    }
                    if(hasAddedInvoices) continue;
                
                // End of Modified by William Tam on 24 Jun 2009, for BM Security
                    settleCount++;
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=subscriberNumber VALUE=\"" + custInvoices[j].subscriberNumber + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceNumber VALUE=\"" + custInvoices[j].invoiceNumber + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSystemInd VALUE=\"" + custInvoices[j].systemInd + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceChargeType VALUE=\"" + custInvoices[j].chargeType + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSettleAmount VALUE=\"" + FormatCurr(custInvoices[j].settleAmount) + "\">");
                    // Added by William Tam on 24 Jun 2009, for BM Sercuity
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceType VALUE=\"" + custInvoices[j].invoiceType + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceDate VALUE=\"" + custInvoices[j].invoiceDate + "\">");
                    frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceOSAmount VALUE=\"" + custInvoices[j].outstandAmount + "\">");

                    if(custInvoices[j].invoiceType == "E") excludeInvoiceCount++;
                    // End of Added by William Tam on 24 Jun 2009, for BM Sercuity
                    //if (custInvoices[j].subscriberNumber == "INTERIM") {
                    //    interimAmount = custInvoices[j].settleAmount;
                    //}
                }
            }
            //frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=interimAmount VALUE=\"" + FormatCurr(interimAmount) + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceCount VALUE=" + settleCount + ">");
            // Added by William Tam on 24 Jun 2009, for BM Security
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=excludeInvoiceCount VALUE=" + excludeInvoiceCount + ">");
            // End of added by William Tam on 24 Jun 2009, for BM Security
        } else {
            var invoice = invoices.item(keys[i]);
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=customerNumber VALUE=\"" + invoice.customerNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=subscriberNumberForPrint VALUE=\"" + invoice.subscriberNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=paymentAmount VALUE=\"" + FormatCurr(payment.paymentAmount) + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=settleAmount VALUE=\"" + FormatCurr(payment.settleAmount) + "\">");
            // Added by William Tam on 24 Jun 2009, for BM Security
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=customerType VALUE=\"\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=startRequst VALUE=\"0\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=excludeInvoiceCount VALUE=0>");
            // End of added by William Tam on 24 Jun 2009, for BM Security

            var charges = invoices.item(keys[i]).charges;
            for (var j = 0; j < charges.length; j++) {
                var charge = charges[chargeItemCount++];
                frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=chargeType VALUE=\"" + charge.chargeType + "\">");
                frmSave.insertAdjacentHTML("beforeEnd", "<INPUT Type=Hidden NAME=chargeAmount VALUE=\"" + FormatCurr(charge.chargeAmount) + "\">");
            }
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=subscriberNumber VALUE=\"" + invoice.subscriberNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceNumber VALUE=\"" + invoice.invoiceNumber + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSystemInd VALUE=\"" + invoice.systemInd + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceChargeType VALUE=\"" + invoice.chargeType + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceSettleAmount VALUE=\"" + FormatCurr(payment.settleAmount) + "\">");
            //frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=interimAmount VALUE=\"0.00\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceCount VALUE=" + (invoice.invoiceNumber == "-" ? 0 : 1) + ">");
            // Added by William Tam on 24 Jun 2009, for BM Security
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceType VALUE=\"" + invoice.invoiceType + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceDate VALUE=\"" + invoice.invoiceDate + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=invoiceOSAmount VALUE=\"" + invoice.outstandAmount + "\">");
            // End of Added by William Tam on 24 Jun 2009, for BM Security
        }

        // Payment details
        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=methodCount VALUE=\"" + method.length + "\">");
        for (var j = 0; j < method.length; j++) {
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=paymentCode VALUE=\"" + convertPaymentCode(method[j].code) + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=reference1 VALUE=\"" + method[j].reference1 + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=reference2 VALUE=\"" + method[j].reference2 + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=methodAmount VALUE=\"" + FormatCurr(method[j].paymentAmount) + "\">");
        }
        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=approvalCode VALUE=\"" + payment.approvalCode + "\">");
        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=remarks VALUE=\"" + payment.remarks + "\">");

        // Advanced Payment
        if (payment.paymentAmount > payment.settleAmount) {
            chargeItemCount++;
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=chargeType VALUE=\"" + customers.item(keys[i]).defaultChargeType + "\">");
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT Type=Hidden NAME=chargeAmount VALUE=\"" + FormatCurr(payment.paymentAmount - payment.settleAmount) + "\">");
        }
        frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=chargeItemCount VALUE=" + chargeItemCount + ">");
    }

//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)        

    var printers = document.all.printers.value;
    var customerNumber =[];
    $("#frmSave input[name='customerNumber']").each(function(){
        customerNumber.push($(this).val());
    })

    var subscriberNumberForPrint =[];
    $("#frmSave input[name='subscriberNumberForPrint']").each(function(){
        subscriberNumberForPrint.push($(this).val());
    })

    var paymentAmount =[];
    $("#frmSave input[name='paymentAmount']").each(function(){
        paymentAmount.push($(this).val());
    })

    var settleAmount =[];
    $("#frmSave input[name='settleAmount']").each(function(){
        settleAmount.push($(this).val());
    })

    var customerType =[];
    $("#frmSave input[name='customerType']").each(function(){
        customerType.push($(this).val());
    })

    var startRequest =[];
    $("#frmSave input[name='startRequest']").each(function(){
        startRequest.push($(this).val());
    })		

    var subscriberNumber =[];
    $("#frmSave input[name='subscriberNumber']").each(function(){
        subscriberNumber.push($(this).val());
    })	

    var invoiceNumber =[];
    $("#frmSave input[name='invoiceNumber']").each(function(){
        invoiceNumber.push($(this).val());
    })	

    var invoiceSystemInd =[];
    $("#frmSave input[name='invoiceSystemInd']").each(function(){
        invoiceSystemInd.push($(this).val());
    })

    var invoiceChargeType =[];
    $("#frmSave input[name='invoiceChargeType']").each(function(){
        invoiceChargeType.push($(this).val());
    })	

    var invoiceSettleAmount =[];
    $("#frmSave input[name='invoiceSettleAmount']").each(function(){
        invoiceSettleAmount.push($(this).val());
    })	

    var invoiceType =[];
    $("#frmSave input[name='invoiceType']").each(function(){
        invoiceType.push($(this).val());
    })

    var invoiceDate =[];
    $("#frmSave input[name='invoiceDate']").each(function(){
        invoiceDate.push($(this).val());
    })	

    var invoiceOSAmount =[];
    $("#frmSave input[name='invoiceOSAmount']").each(function(){
        invoiceOSAmount.push($(this).val());
    })	

    var invoiceCount =[];
    $("#frmSave input[name='invoiceCount']").each(function(){
        invoiceCount.push($(this).val());
    })

    var excludeInvoiceCount =[];
    $("#frmSave input[name='excludeInvoiceCount']").each(function(){
        excludeInvoiceCount.push($(this).val());
    })	

    var chargeType =[];
    $("#frmSave input[name='chargeType']").each(function(){
        chargeType.push($(this).val());
    })	

    var chargeAmount =[];
    $("#frmSave input[name='chargeAmount']").each(function(){
        chargeAmount.push($(this).val());
    })

    var methodCount =[];
    $("#frmSave input[name='methodCount']").each(function(){
        methodCount.push($(this).val());
    })

    var paymentCode =[];
    $("#frmSave input[name='paymentCode']").each(function(){
        paymentCode.push($(this).val());
    })	

    var reference1 =[];
    $("#frmSave input[name='reference1']").each(function(){
        reference1.push($(this).val());
    })

    var reference2 =[];
    $("#frmSave input[name='reference2']").each(function(){
        reference2.push($(this).val());
    })	

    var methodAmount =[];
    $("#frmSave input[name='methodAmount']").each(function(){
        methodAmount.push($(this).val());
    })

    var approvalCode =[];
    $("#frmSave input[name='approvalCode']").each(function(){
        approvalCode.push($(this).val());
    })

    var remarks =[];
    $("#frmSave input[name='remarks']").each(function(){
        remarks.push($(this).val());
    })		

    var chargeItemCount =[];
    $("#frmSave input[name='chargeItemCount']").each(function(){
        chargeItemCount.push($(this).val());
    })

    var content = {
        action: "validate",
        printer: printers,
        customerNumber: customerNumber,
        subscriberNumberForPrint: subscriberNumberForPrint,
        paymentAmount: paymentAmount,
        settleAmount: settleAmount,
        customerType: customerType,
        startRequest: startRequest,
        subscriberNumber: subscriberNumber,
        invoiceNumber: invoiceNumber,
        invoiceSystemInd: invoiceSystemInd,
        invoiceChargeType: invoiceChargeType,
        invoiceSettleAmount: invoiceSettleAmount,
        invoiceType: invoiceType,
        invoiceDate: invoiceDate,
        invoiceOSAmount: invoiceOSAmount,
        invoiceCount: invoiceCount,
        excludeInvoiceCount: excludeInvoiceCount,
        chargeType:  chargeType,		
        chargeAmount: chargeAmount,
        methodCount: methodCount,
        paymentCode: paymentCode,
        reference1: reference1,
        reference2: reference2,
        methodAmount:  methodAmount,
        approvalCode: approvalCode,
        remarks: remarks,
        chargeItemCount: chargeItemCount
    };

    var isReturnSaveFunc = false;   // m1
    dojo.io.bind({
        url:     "/servlet/fes.sa.settle.SASettleSaveServlet",
        method:  "post",
        content: content,
        error: function(type, data, evt) {
            //verifiedOk = false;
            saveError();
//Modified by Zeno Liang on 18 Mar 2025, for Precious Metals and Stones Dealer ("PMSD") Registration
			//alert("SASettlePage validate() System Error:" + data);
			alert(data);
//End of modified by Zeno Liang on 18 Mar 2025, for Precious Metals and Stones Dealer ("PMSD") Registration	
        },
        load: function(type, result, evt) {
            if (result.returnCode == 0){
                //alert("doDevicePayment");
                var payResult = doDevicePayment(paymentCode, reference1, reference2, methodAmount);
                //alert("After doDevicePayment payResult[0]="+payResult[0]);
                // m1 - Begin
                /*
                if(payResult[0] === true) {
                    verifiedOk = true;
                    devicePayResult = true;
                    rtnA8AuthCode = GetParameterValues(payResult[2],"a8AuthCode");
                    rtnA8PayCode = GetParameterValues(payResult[2],"a8PayCode");
                    rtnA8CashDollar = GetParameterValues(payResult[2],"a8CashDollar");
                    rtnA8MaskedCardNo = GetParameterValues(payResult[2],"a8MaskedCardNo");                   
                    rtnA8BatchNo = GetParameterValue(payResult[2],"a8BatchNo");
                    
                    rtnShkpBatchNo = GetParameterValue(payResult[2],"ShkpBatchNo");                
                    rtnShkpTransactionId = GetParameterValue(payResult[2],"ShkpTransactionId");                
                    rtnShkpPayCode = GetParameterValue(payResult[2],"ShkpPayCode");
                    //Added by Kent Li for refund SHKP on 20230714
		            if(typeof rtnShkpTransactionId !== "undefined" && rtnShkpTransactionId != "") {
		                frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=ShkpTransactionId VALUE=\"" + rtnShkpTransactionId + "\">");
                    }
                    //End Added by Kent Li for refund SHKP on 20230714
                } else {
                    verifiedOk = false;
                    devicePayResult = false;
		    saveError();
                    alert(payResult[1]);
                }
                */
                isReturnSaveFunc = true;
                // m1 - End
            } else {
                //a8Status = "OK";
                //verifiedOk = false;
                saveError();
			   
//Modified by Zeno Liang on 18 Mar 2025, for Precious Metals and Stones Dealer ("PMSD") Registration
                //alert("SASettlePage validate() result.returnCode="+result.returnCode+" ,result.returnMessage="+result.returnMessage); 
                var msg = result.returnMessage;	
                var index = msg.indexOf(']');
                if (index !== -1) {
					alert(msg.substring(0, index + 1) + "\r\n" + msg.substring(index + 1));
                } else {
                    alert(msg);
                }
//End of modified by Zeno Liang on 18 Mar 2025, for Precious Metals and Stones Dealer ("PMSD") Registration	
            }
        },
        mimetype: "text/json",
        sync: true,
        useCache: false,
        preventCache: false
    });
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)

// m1 - Begin
/*
//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
    //Generate Receipt
    if(verifiedOk === true) {
       //alert("verifiedOk="+verifiedOk);
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
       frmSave.submit();
       document.body.style.cursor = "wait";
       if(window.showModalDialog){  // m1
       util.disableDocument(document);
       }else{   // m1 - Begin
        util.disableControls(document.getElementsByTagName("INPUT"));
        util.disableControls(document.getElementsByTagName("TEXTAREA"));
       }    // m1 - End
//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)	
    }
    
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
*/
    if(!isReturnSaveFunc){
        afterSaveVerify();
    }
// m1 - End
}

// m1 - Begin
function handleDoDevicePayment(payResult){
    if(payResult[0] === true) {
        verifiedOk = true;
        devicePayResult = true;
        rtnA8AuthCode = GetParameterValues(payResult[2],"a8AuthCode");
        rtnA8PayCode = GetParameterValues(payResult[2],"a8PayCode");
        rtnA8CashDollar = GetParameterValues(payResult[2],"a8CashDollar");
        rtnA8MaskedCardNo = GetParameterValues(payResult[2],"a8MaskedCardNo");                   
        rtnA8BatchNo = GetParameterValue(payResult[2],"a8BatchNo");
        
        rtnShkpBatchNo = GetParameterValue(payResult[2],"ShkpBatchNo");                
        rtnShkpTransactionId = GetParameterValue(payResult[2],"ShkpTransactionId");                
        rtnShkpPayCode = GetParameterValue(payResult[2],"ShkpPayCode");
        //Added by Kent Li for refund SHKP on 20230714
        if(typeof rtnShkpTransactionId !== "undefined" && rtnShkpTransactionId != "") {
            frmSave.insertAdjacentHTML("beforeEnd", "<INPUT TYPE=Hidden NAME=ShkpTransactionId VALUE=\"" + rtnShkpTransactionId + "\">");
        }
        //End Added by Kent Li for refund SHKP on 20230714
    } else {
        verifiedOk = false;
        devicePayResult = false;
        saveError();
        alert(payResult[1]);
    }
    afterSaveVerify();
}
function afterSaveVerify(){
    if(verifiedOk === true) {
        frmSave.submit();
        document.body.style.cursor = "wait";
        if(window.showModalDialog){
        util.disableDocument(document);
        }else{
            util.disableControls(document.getElementsByTagName("INPUT"));
            util.disableControls(document.getElementsByTagName("TEXTAREA"));
        }
    }
}
// m1 - End

function convertPaymentCode(code) {
    switch (code) {
        case "A": return "AE";
        //Added by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
        case "B": return "PAYME";
        //End Added by Devin Chen on 18/07/2022 for Payme integration testing (SR0032606)
        case "C": return "CASH";
        //case "D": return "DINERS";
        case "E": return "EPS";
        case "M": return "MASTER";
        case "P": return "PRESALE";
        case "Q": return "CHQ";
        case "V": return "VISA";
        // Added by William Tam on 23 May 2011, for new payment code PAYWARE
        case "W": return "PAYWARE";
        // End of added by William Tam on 23 May 2011, for new payment code PAYWARE
        // Added by Wilson Ng on 27-08-2013 for new payment code MPOS (201308220014)
        //Modified by Ken Cheng on 25-08-2016 for new payment ALIPAY and WECHAT
        case "O": return "ALIPAY";
        //Modified by Ken Cheng on 25-08-2016 for new payment ALIPAY and WECHAT
        // End added by Wilson Ng on 27-08-2013 for new payment code MPOS (201308220014)
        // Added by Wilson Ng on 07-03-2012 for adding new payment code CUP
        case "U": return "CUP-CARD";
        // End added by Wilson Ng on 07-03-2012 for adding new payment code CUP
        // Added by Wilson Ng on 10-04-2012 for adding new payment code RMB-CARD
        case "R": return "RMB-CARD";
        // End added by Wilson Ng on 10-04-2012 for adding new payment code RMB-CARD
        // Added by Wilson Ng on 08-09-2014 for new payment code GPM4230 (201409020037)
        //Modified by Ken Cheng on 25-08-2016 for new payment ALIPAY and WECHAT
        case "G": return "WECHAT";
        //End odified by Ken Cheng on 25-08-2016 for new payment ALIPAY and WECHAT
        // End added by Wilson Ng on 08-09-2014 for new payment code GPM4230 (201409020037)
        // Added by Wilson Ng on 20-04-2015 for adding new payment codes HSBVMCUP, N-HSBVM, N-HSBCUP (201503300025)
        case "H": return "HSBVMCUP";
        case "N": return "N-HSBVM";
        case "S": return "N-HSBCUP";
        // End added by Wilson Ng on 20-04-2015 for adding new payment codes HSBVMCUP, N-HSBVM, N-HSBCUP (201503300025)
        // Commented by Billy Pang on 10/06/2021 to align production
        // // Added by Wilson Ng on 24-08-2015 for adding new payment codes BHS12, BHS24, B-HSBVM, BN-HSBVM (201508010001)
        // case "B": return "B-HSBVM";
        // case "F": return "BN-HSBVM";
        // // End added by Wilson Ng on 24-08-2015 for adding new payment codes BHS12, BHS24, B-HSBVM, BN-HSBVM (201508010001)
        // End Commented by Billy Pang on 10/06/2021 to align production
        // Added by Billy Pang on 27/07/2017 for adding new payment code HSBCD (201707260038)
        case "D": return "HSBCD";
        // End Added by Billy Pang on 27/07/2017 for adding new payment code HSBCD (201707260038)
        // Added by Billy pang on 2/11/2018 for adding new payment code TTREMIT , ATMTRF
        case "T": return "TTREMIT";
        case "L": return "ATMTRF";
        // End Added by Billy pang on 2/11/2018 for adding new payment code TTREMIT , ATMTRF
        // Added by Billy Pang on 10/06/2021 for adding new payment code OCTOC (SR0024972)
        case "Z": return "OCTOC";
        // End Added by Billy Pang on 10/06/2021 for adding new payment code OCTOC (SR0024972)
        //Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone phrase 2 (SR0030420)
        case "K": return "SHKP";
        //End Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone phrase 2 (SR0030420)
        //Added by Devin Chen on 18/07/2022 for BoC Pay (SR0033829)
        case "Y": return "BOCPAY";
        //End Added by Devin Chen on 18/07/2022 for BoC Pay (SR0033829)
    }
}


// Receives notification when an error occurs while saving
function saveError() {
    saveStatus = "idle";
    if(window.showModalDialog){ // m1
    util.enableDocument(document);
    }else{  // m1 - Begin
        util.enableControls(document.getElementsByTagName("INPUT"), true);
        util.enableControls(document.getElementsByTagName("TEXTAREA"), true);
    }   // m1 - End
    document.body.style.cursor = "auto";
}


// Receives notification when save is complete
function saveComplete() {
    if (saveStatus == "processing" && fraSave.document.readyState == "complete") {
        alert("<%=messageBundle.getString("SaveError")%>");
        saveError();
    }
}

//Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)        
    function doDevicePayment(paymentCode, reference1, reference2, payAmount) {
        var receiptUrl = "";
        var pResult = new Array(6);
        var errorMsg = "";
        var refundResult = new Array(4);
        var pdBatchNo = "";
        var pdTransactionId = "";
        var pdPayCode = "";

        // m1 - Begin
        doCSPDResult = null;
        isCallingDoCSPDPayment = true;

        if(window.showModalDialog){ // m1 - End
//Start modified by Kent Li on 20240218 for add callFrom
        //pResult = doCSPointDollarPayment(paymentCode, reference1, reference2, payAmount);
        pResult = doCSPointDollarPayment(paymentCode, reference1, reference2, payAmount, "CashSettle_Postpaid");
//End modified by Kent Li on 20240218 for add callFrom
            afterSearchSHKP(paymentCode, reference1, reference2, payAmount, pResult);    // m1 - Begin
        }else{
            doCSPointDollarPayment(paymentCode, reference1, reference2, payAmount, "CashSettle_Postpaid");
            doPaymentInterval = setInterval(function(){
                if(isCallingDoCSPDPayment == false && doCSPDResult != null){
                    afterSearchSHKP(paymentCode, reference1, reference2, payAmount, doCSPDResult);
                    clearInterval(doPaymentInterval);
                }
            }, 100);
            return;
        }
        /*
        if(pResult[0] === true) {
            if(pResult[2] != "") {
                receiptUrl = pResult[2];
            }
            pdBatchNo = pResult[3];
            pdTransactionId = pResult[4];
            pdPayCode = pResult[5];
            
            //alert("the Point url="+receiptUrl);

            pResult = doCSA8Payment(paymentCode, reference1, reference2, payAmount);
            if(pResult[0] === true) {
                if(receiptUrl != "" && pResult[2] != "") {
                    receiptUrl = receiptUrl + "&" + pResult[2];
                } else if (receiptUrl == "" && pResult[2] != "") {
                    receiptUrl = pResult[2];
                }
                pResult[2] = receiptUrl;

                //alert("the A8 url="+receiptUrl);
            } else {
                if(pdTransactionId != "") {
                    //void the Point payment
                    refundResult=doCSPointDollarRefund(pdTransactionId);
                    if(refundResult[0] === true) {
                        pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " has been refunded. Refund id="+refundResult[3];
                    } else {
                        pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " cannot be refunded due to "+ refundResult[1];
                    }                    
                }
            }
        }

        return pResult;
        */
        // m1 - End
    }
//End Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
// m1 - Begin
function afterSearchSHKP(paymentCode, reference1, reference2, payAmount, result){
    var pResult = new Array(6);
    var receiptUrl = "";
    var pdBatchNo = "";
    var pdTransactionId = "";
    var pdPayCode = "";
    
    pResult = result;
    if(pResult[0] === true) {
        if(pResult[2] != "") {
            receiptUrl = pResult[2];
        }
        pdBatchNo = pResult[3];
        pdTransactionId = pResult[4];
        pdPayCode = pResult[5];
        
        //alert("the Point url="+receiptUrl);

        // m1 - Begin
        isCallingDoCSA8Payment = true;
        doCSA8Result = null;
        // m1 - End

        if(window.showModalDialog){ // m1
        pResult = doCSA8Payment(paymentCode, reference1, reference2, payAmount);
        afterDoCSA8Payment(pResult, receiptUrl, pdTransactionId);  // m1 - Begin
        }else{
            doCSA8Payment(paymentCode, reference1, reference2, payAmount);
            a8PaymentInterval = setInterval(function(){
                if(isCallingDoCSA8Payment == false && doCSA8Result != null){
                    afterDoCSA8Payment(doCSA8Result, receiptUrl, pdTransactionId);
                    clearInterval(a8PaymentInterval);
                }
            }, 100);
            return;
        }
        /*
        if(pResult[0] === true) {
            if(receiptUrl != "" && pResult[2] != "") {
                receiptUrl = receiptUrl + "&" + pResult[2];
            } else if (receiptUrl == "" && pResult[2] != "") {
                receiptUrl = pResult[2];
            }
            pResult[2] = receiptUrl;

            //alert("the A8 url="+receiptUrl);
        } else {
            if(pdTransactionId != "") {
                //void the Point payment
                refundResult=doCSPointDollarRefund(pdTransactionId);
                if(refundResult[0] === true) {
                    pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " has been refunded. Refund id="+refundResult[3];
                } else {
                    pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " cannot be refunded due to "+ refundResult[1];
                }                    
            }
        }
        */
        // m1 - End
    }else{
        handleDoDevicePayment(pResult);
    }
}

function afterDoCSA8Payment(pResult, receiptUrl, pdTransactionId){
    var refundResult = new Array(4);
    if(pResult[0] === true) {
        if(receiptUrl != "" && pResult[2] != "") {
            receiptUrl = receiptUrl + "&" + pResult[2];
        } else if (receiptUrl == "" && pResult[2] != "") {
            receiptUrl = pResult[2];
        }
        pResult[2] = receiptUrl;

        //alert("the A8 url="+receiptUrl);
    } else {
        if(pdTransactionId != "") {
            //void the Point payment
            refundResult=doCSPointDollarRefund(pdTransactionId);
            if(refundResult[0] === true) {
                pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " has been refunded. Refund id="+refundResult[3];
            } else {
                pResult[1] = pResult[1] + " The redeemed point dollars for transaction ID="+pdTransactionId+ " cannot be refunded due to "+ refundResult[1];
            }                    
        }
    }
    handleDoDevicePayment(pResult);
}
// m1 - End

// m1 - Begin
var showResultWin;
var showResultInterval;
var printReceiptWin = true;
// m1 - End
// Receives notification when receipts are created
//function createReceipts(receipts) {
function createReceipts(receipts, subrNums, custNums) {
    //Added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
    //alert("createReceipts A8 receipts"+receipts.join("/")+",devicePayResult="+devicePayResult+",rtnA8BatchNo="+rtnA8BatchNo);

    if (devicePayResult === true && (typeof rtnA8BatchNo !== "undefined" && rtnA8BatchNo != "") ) {
        var updateA8PaymentReceiptResult = false;
        updateA8PaymentReceiptResult = updateA8PaymentReceipt(receipts, rtnA8PayCode, rtnA8MaskedCardNo, rtnA8AuthCode, rtnA8CashDollar, rtnA8BatchNo);
        //alert("updateA8PaymentReceiptResult: errorCode="+updateA8PaymentReceiptResult[0] + " ,errorMsg="+updateA8PaymentReceiptResult[1]);
    }
    //End added by Alex Lam on 20210714 for Smartone Store card terminal interface (SR0029902)
    
    //Added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone (SR0030420)
    //alert("createReceipts SHKP receipts"+receipts.join("/")+",devicePayResult="+devicePayResult+",rtnShkpTransactionId="+rtnShkpTransactionId);
        
    if (devicePayResult === true && (typeof rtnShkpTransactionId !== "undefined" && rtnShkpTransactionId != "") ) {
        var updateShkpReceiptResult = false;
        updateShkpReceiptResult = updateThePointReceipt(receipts, rtnShkpPayCode, rtnShkpTransactionId, rtnShkpBatchNo);
        //alert("updateShkpReceiptResult errorCode="+updateShkpReceiptResult[0] + " ,errorMsg="+updateShkpReceiptResult[1]);
    }
    //End added by Alex Lam on 20220307 Point Dollar  The Point Cash Rebate Program - Smartone (SR0030420)
    
    //saveStatus = "success";
    if(window.showModalDialog){ // m1
    //Added by Ken Cheng on 22-07-2016 for wifiEgg
    var result = showModalDialog("/servlet/fes.sa.settle.SASettleSaveServlet?action=showResult&receiptNumber=" + receipts.join("&receiptNumber="), null, "dialogWidth:460px; dialogHeight:300px; dialogTop:200px; dialogLeft: 200px;");
    //End added by Ken Cheng on 22-07-2016 for wifiEgg
    }else{  // m1 - Begin
        showResultWin = window.open("/servlet/fes.sa.settle.SASettleSaveServlet?action=showResult&receiptNumber=" + receipts.join("&receiptNumber="), 
                                "_showResult", "width=460px,height=300px");
        childWins.push(showResultWin);
        showResultInterval = setInterval(function(){
            if(showResultWin == null || (showResultWin != null && showResultWin.closed)){
                if (document.all.printer.value == "") {
                    printReceiptWin = window.open("/jsp/fes/sa/printReceiptForm.jsp?receiptNos="+receipts.join("&receiptNos=")+"&subrNums="+subrNums.join("&subrNums=")+"&custNums="+custNums.join("&custNums=")+"&logid=0", "_printReceipt","width=750px,height=630px");
                    childWins.push(printReceiptWin);
                }else{
                    printReceiptWin = null;
                }
                clearInterval(showResultInterval);
            }
        }, 100);
        return;
    }   // m1 - End

    // Added by William Tam on 1 Aug 2011, for Project Thunder
    if (document.all.printer.value == "") {
        showModalDialog("/jsp/fes/sa/printReceiptForm.jsp", {receiptNos: receipts, subrNums: subrNums, custNums: custNums, logid: 0 },
                        "dialogWidth:750px; dialogHeight:630px; dialogTop:20px; dialogLeft: 0px;");
    }
    // End of added by William Tam on 1 Aug 2011, for Project Thunder

    handleShowResult(result);   // m1 - Begin
    
    /*
    // Added by Wilson Ng on 27-06-2012 for adding warning of reconnection
    if (result != null) {
        if (result.isReconnectWarning) {
            showModalDialog("/jsp/fes/sa/settle/SASettleReconnectWarning.jsp", result, "dialogWidth:510px; dialogHeight:350px; dialogTop:200px; dialogLeft: 200px;");
        }
    }
    // End added by Wilson Ng on 27-06-2012 for adding warning of reconnection
    
    window.location.reload(true);
     */
     // m1 - End
}

// m1 - Begin
var printReceiptInterval;
var tmpShowResult;
function handleShowResultForChrome(result){
    tmpShowResult = result;
    printReceiptInterval = setInterval(function(){
        if(printReceiptWin == null || (printReceiptWin != null && printReceiptWin.closed)){
            handleShowResult(result);
            clearInterval(printReceiptInterval);
        }
    }, 100);
}

var reconnectWarnInter;
function handleShowResult(result){
    if(!window.showModalDialog){
        result = tmpShowResult;
    }
    if (result != null) {
        if (result.isReconnectWarning) {
            if(window.showModalDialog){
            showModalDialog("/jsp/fes/sa/settle/SASettleReconnectWarning.jsp", result, "dialogWidth:510px; dialogHeight:350px; dialogTop:200px; dialogLeft: 200px;");
            window.location.reload(true);
            }else{
                var reconnectWin = window.open("/jsp/fes/sa/settle/SASettleReconnectWarning.jsp", "_reconWarn", "width=510px,height=350px");
                childWins.push(reconnectWin);
                reconnectWarnInter = setInterval(function(){
                    if(reconnectWin == null || (reconnectWin != null && reconnectWin.closed)){
                        window.location.reload(true);
                        clearInterval(reconnectWarnInter);
                    }
                }, 100);
            }
        }else{
            window.location.reload(true);
        }
    }
}
// m1 - End


// Receives notification when a key is pressed
function handleKey(event) {
    // Modified by William Tam on 15 Sep 2015, replaec Hotkey
    //if (event.keyCode == 7 && saveStatus == "idle") { // Ctrl-G
    //if (event.keyCode == 71 && event.altKey && saveStatus == "idle") { // ALT-G
    if (event.keyCode == 17 && saveStatus == "idle") { // Ctrl+Q
    //End of Modified by William Tam on 15 Sep 2015, replaec Hotkey
        var valid = true;
        switch (event.srcElement.name) {
            case "paymentMethod":
                valid = changePaymentCode(event.srcElement);
                break;
            case "paymentAmount":
                valid = changePaymentAmount(event.srcElement);
                break;
            case "settleAmount":
                valid = changeSettleAmount(event.srcElement);
                break;
            case "settleSubtotal":
                valid = changeSettleSubtotal(event.srcElement);
                break;
            case "chargeAmount":
                valid = changeChargeAmount(event.srcElement);
                break;
        }

        if (valid) util.execute('save()', true);
    }
    KeyHandler.handleKey(event);
}

// Added by William Tam on 24 Jun 2009, for BM Security
function cloneArray (array) {
        var a = [];
        if (array != null && array.length != null) {
            for (var i = 0; i < array.length; i++) {
                a.push(array[i]);
            }
        }
        return a;
    }

function calInvoicesTotal(inputInvoices) {
    var total = 0;
    for(var i=0; i <inputInvoices.length; i++){
        if(inputInvoices[i].settleAmount > 0)
            total = FormatCurr(new Number(total) + new Number(inputInvoices[i].settleAmount));
    }
    return total;
}
// End of Added by William Tam on 24 Jun 2009, for BM Security

// m1 - Begin
function handySubmit(){
    if(event.keyCode == 13){
        event.srcElement.blur();
        setTimeout('document.getElementById("btnSearch").click()', 100);
    }
}
if(!window.ActiveXObject){
    window.addEventListener('keypress', function(){
        handySubmit();
    });
}
// m1 - End

//--></SCRIPT>
</HEAD>

<BODY ONKEYPRESS="handleKey(event)"
      ONMOUSEOVER="ImageHandler.handleMouseOver(event.srcElement)"
      ONMOUSEOUT="ImageHandler.handleMouseOut(event.srcElement)"
      style="overflow: hidden;"
><!--m1-->
<DIV ID=divSettlement>
<!--m1 - Begin-->
<!--<FORM NAME=frmSearch ONSUBMIT="search('', this.handleUser.value, this.searchNumber.value); return false;">-->
<FORM NAME=frmSearch ONSUBMIT="return false;" onKeyPress="handySubmit()">
<!--m1 - End-->
<TABLE WIDTH=740>
  <TR>
    <TD WIDTH=400 ALIGN="left"><IMG SRC="/images/<%=imageBundle.getString("HeadSettlement")%>"></TD>
    <TD WIDTH=340 ALIGN="right"><IMG SRC="/images/logo.gif"></TD>
  </TR>
</TABLE>
<TABLE WIDTH=740 CELLPADDING=0 BORDER=0>  
  <COL WIDTH=140>
  <COL WIDTH=160>
  <COL WIDTH=140>
  <COL WIDTH=140>
  <COL WIDTH=140>
 <TR> 
    <TD CLASS=cell-label-IP><%=labelBundle.getString("CreateUser")%></TD>
    <TD CLASS=cell-data-IP>
<%  if (!AppInfo.isSalesmanLogin(session)) { %>
      <INPUT TYPE=Text NAME=handleUser VALUE="<%=settleBean.getHandleUser()%>" MAXLENGTH=25 TABINDEX=-1 CLASS=textbox-IP STYLE="width:130px;font-size:15pt;">
<%  } else { %>
      <INPUT TYPE=Text NAME=handleUser VALUE="<%=session.getAttribute("FES.salesman")%>" TABINDEX=-1 READONLY CLASS=textbox-read-IP STYLE="width:130px;font-size:15pt;">
<%  } %>
    <INPUT TYPE=Submit TABINDEX=-1 STYLE='display: none;width:0px'><!--m1-->
    </TD>
    <TD CLASS=cell-data-IP>
        <BUTTON ID=btnSearch CLASS=button TABINDEX=3 ONCLICK="search('', frmSearch.handleUser.value, frmSearch.searchNumber.value)" TITLE="<%=labelBundle.getString("OsInvoices")%>" style="width:120px;height:30px;"><%=labelBundle.getString("Search")%></BUTTON>
    </TD>
    <TD CLASS=cell-data-IP>
        <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
        <%-- <BUTTON ID=btnClear CLASS=button TABINDEX=4 ONCLICK="window.location.reload(true); return false" style="width:120px;height:30px;font-size:10pt;"><%=labelBundle.getString("Clear")%></BUTTON> --%>
        <BUTTON ID=btnClear CLASS=button TABINDEX=5 ONCLICK="window.location.reload(true); return false" style="width:120px;height:30px;font-size:10pt;"><%=labelBundle.getString("Clear")%></BUTTON>
        <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
    </TD>
    <TD CLASS=cell-data-IP> 
        <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
        <%-- <BUTTON ID=btnClose CLASS=button TABINDEX=5 ONCLICK="window.close(); return false" style="width:120px;height:30px;font-size:10pt"><%=labelBundle.getString("Close")%></BUTTON> --%>
        <BUTTON ID=btnClose CLASS=button TABINDEX=7 ONCLICK="window.close(); return false" style="width:120px;height:30px;font-size:10pt"><%=labelBundle.getString("Close")%></BUTTON>
        <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
    </TD>
  </TR>
  <TR>
    <TD CLASS=cell-label-IP><%=labelBundle.getString("SearchNumber")%></TD>
    <TD CLASS=cell-data-IP>
        <INPUT TYPE=Text NAME=searchNumber MAXLENGTH=20 TABINDEX=1 CLASS=textbox-IP STYLE="width:130px; font-size:15pt;" HANDLEKEY="uppercase">
    </TD>
    <TD CLASS=cell-data-IP> 
        <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
        <%-- <BUTTON ID=btnSave CLASS=button TABINDEX=6 ONCLICK="util.execute('save()', true); return false" TITLE="<%=labelBundle.getString("GenerateReceipt")%> (CTRL+Q)" style="width:120px;height:30px;font-size:10pt"><%=labelBundle.getString("Save")%></BUTTON> --%>
        <BUTTON ID=btnSave CLASS=button TABINDEX=4 ONCLICK="util.execute('save()', true); return false" TITLE="<%=labelBundle.getString("GenerateReceipt")%> (CTRL+Q)" style="width:120px;height:30px;font-size:10pt"><%=labelBundle.getString("Save")%></BUTTON>
        <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
    </TD>
    <TD CLASS=cell-data-IP>
    <!--modified by Wilson Hung on 16 AUG 2017 for AccessoriesSales invoicing flow 
        <BUTTON ID=btnProdSales CLASS=button TABINDEX=7 ONCLICK="issueInvoice2(frmSearch.handleUser.value)" TITLE="Product" style="width:120px;height:30px;"><%=labelBundle.getString("ProductInvoice")%></BUTTON>
    -->
        <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
        <%-- <BUTTON ID=btnProdSales CLASS=button TABINDEX=7 ONCLICK="issueInvoice(frmSearch.searchCustNo.value,frmSearch.searchNumber.value,frmSearch.handleUser.value)" TITLE="Product" style="width:120px;height:30px;"><%=labelBundle.getString("AccessoriesSales")%></BUTTON> --%>
        <BUTTON ID=btnProdSales CLASS=button TABINDEX=6 ONCLICK="issueInvoice(frmSearch.searchCustNo.value,frmSearch.searchNumber.value,frmSearch.handleUser.value)" TITLE="Product" style="width:120px;height:30px;"><%=labelBundle.getString("AccessoriesSales")%></BUTTON>
        <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
    <!--End modified by Wilson Hung on 16 AUG 2017 for AccessoriesSales invoicing flow -->
    </TD>
    <TD CLASS=cell-data-IP>
        <BUTTON ID=btnReprint CLASS=button TABINDEX=8 ONCLICK="window.open('/jsp/pos/reprint/reprintReceiptPage.jsp?fromSettlement=Y', 'ReprintReceipt','toolbar=no,WIDTH=500,HEIGHT=580,left=0,top=0,SCROLLING=Yes')" TITLE="<%=labelBundle.getString("ReprintReceipt")%>" style="width:120px;height:30px;"><%=labelBundle.getString("ReprintReceipt")%></BUTTON>
    </TD>
  </TR>
  <TR>
    <TD CLASS=cell-label-IP><%=labelBundle.getString("CustomerNumber")%></TD>
    <TD CLASS=cell-data-IP>
        <INPUT TYPE=Text NAME=searchCustNo MAXLENGTH=20 TABINDEX=2 CLASS=textbox-IP STYLE="width:130px; font-size:15pt;" HANDLEKEY="uppercase">
    </TD>
    <!-- Modified by Billy Pang on 25/5/2017 for adding Kiosk OS -->
    <TD CLASS=cell-data-IP colspan="3">  
        <TABLE>
            <TD CLASS=cell-label-IP STYLE="font-size:10pt; width:210px; background-color: #E1F4FF; ">Kiosk O/S (A/C) : 
                <!-- Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk -->
                <!-- <input type="text" id="kioskCustAmount" name="kioskCustAmount" class="textbox-read-IP" value="0.00" style="width:80px; font-size:10pt; " readonly> -->
                <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
                <%-- <input type="text" id="kioskCustAmount" name="kioskCustAmount" class="textbox-read-IP" value="0.0" style="width:80px; font-size:10pt; " readonly> --%>
                <input type="text" id="kioskCustAmount" name="kioskCustAmount" tabindex=-1 class="textbox-read-IP" value="0.0" style="width:80px; font-size:10pt; " readonly>
                <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
                <!-- End Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk -->
            </TD>
            <TD CLASS=cell-label-IP STYLE="font-size:10pt; width:210px; background-color: #E1F4FF; ">Kiosk O/S (subr) : 
                <!-- Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk -->
                <!-- <input type="text" id="kioskSubrAmount" name="kioskSubrAmount" class="textbox-read-IP" value="0.00" style="width:80px; font-size:10pt;" readonly> -->
                <%-- Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
                <%-- <input type="text" id="kioskSubrAmount" name="kioskSubrAmount" class="textbox-read-IP" value="0.0" style="width:80px; font-size:10pt;" readonly> --%>
                <input type="text" id="kioskSubrAmount" name="kioskSubrAmount" tabindex=-1 class="textbox-read-IP" value="0.0" style="width:80px; font-size:10pt;" readonly>
                <%-- End Modified by Billy Pang on 23/05/2019 for change the tab sequence (SR0005230) --%>
                <!-- End Modified by Billy Pang on 6/6/2017 for decimal places align with kiosk -->
            </TD>
        </table>
    </TD>
    <!-- End Modified by Billy Pang on 25/5/2017 for adding Kiosk OS -->
  </TR>
  <TR>
    <TD CLASS=cell-data colspan=5><FONT COLOR="#0066CC" SIZE=1><%=messageBundle.getString("advPayExclude")%></FONT></TD>    
  </TR>
</TABLE>
<!--TABLE WIDTH=740>
  <TR> 
    <TD WIDTH=100 CLASS=cell-label-IP><%=labelBundle.getString("CreateUser")%></TD>
    <TD WIDTH=280 CLASS=cell-data>
<%  if (!AppInfo.isSalesmanLogin(session)) { %>
      <INPUT TYPE=Text NAME=handleUser VALUE="<%=settleBean.getHandleUser()%>" MAXLENGTH=25 TABINDEX=1 CLASS=textbox-IP STYLE="width:120px">
<%  } else { %>
      <INPUT TYPE=Text NAME=handleUser VALUE="<%=session.getAttribute("FES.salesman")%>" TABINDEX=-1 READONLY CLASS=textbox-read-IP STYLE="width:120px">
<%  } %>
      <INPUT TYPE=Submit TABINDEX=-1 STYLE='width:0px'>
      <IMG SRC="/images/fes/sa/settle/sa-new.gif" TITLE="Product" STYLE="vertical-align:middle; cursor:hand; width:40px; height:40px;" ONCLICK="issueInvoice2(frmSearch.handleUser.value)">
    </TD>
    <TD WIDTH=280 ALIGN=right>
      <BUTTON ID=btnSave CLASS=button TABINDEX=4 ONCLICK="util.execute('save()', true); return false" TITLE="<%=labelBundle.getString("GenerateReceipt")%> (Ctrl-G)" style="width:60px;height:30px;font-size:10pt"><%=labelBundle.getString("Save")%></BUTTON>&nbsp;&nbsp;&nbsp;&nbsp;
      <BUTTON CLASS=button TABINDEX=4 ONCLICK="window.location.reload(true); return false" style="width:60px;height:30px;font-size:10pt"><%=labelBundle.getString("Clear")%></BUTTON>
    </TD>
    <TD WIDTH=80 ALIGN=right>
      <BUTTON CLASS=button TABINDEX=4 ONCLICK="window.close(); return false" style="width:60px;height:30px;font-size:10pt"><%=labelBundle.getString("Close")%></BUTTON>
    </TD>
  </TR>

  <TR>
    <TD CLASS=cell-label-IP><%=labelBundle.getString("SearchNumber")%></TD>
    <TD CLASS=cell-data>
      <INPUT TYPE=Text NAME=searchNumber MAXLENGTH=20 TABINDEX=1 CLASS=textbox-IP STYLE="width:120px; font-size:15pt;" HANDLEKEY="uppercase">&nbsp;&nbsp;
      <IMG SRC="/images/fes/sa/settle/sa-search.gif" TITLE="<%=labelBundle.getString("OsInvoices")%>"       STYLE="vertical-align:middle; cursor:hand; width:40px; height:40px;" ONCLICK="search('', frmSearch.handleUser.value, frmSearch.searchNumber.value)">&nbsp;&nbsp;&nbsp;
      <IMG SRC="/images/fes/sa/settle/sa-new.gif"    TITLE="<%=labelBundle.getString("AccessoriesSales")%>" STYLE="vertical-align:middle; cursor:hand; width:40px; height:40px;" ONCLICK="search('issueInvoice', frmSearch.handleUser.value, frmSearch.searchNumber.value)">&nbsp;&nbsp;&nbsp;
      <IMG SRC="/images/fes/sa/settle/sa-printer.gif"    TITLE="<%=labelBundle.getString("ReprintReceipt")%>" STYLE="vertical-align:middle; cursor:hand; width:40px; height:40px;" ONCLICK="window.open('/jsp/pos/reprint/reprintReceiptPage.jsp?', 'ReprintReceipt','toolbar=no,WIDTH=500,HEIGHT=400,left=0,top=0,SCROLLING=Yes')">
    </TD>
    <TD><FONT COLOR="#0066CC" SIZE="1"><%=messageBundle.getString("advPayExclude")%></FONT></TD>    
  </TR>
</TABLE-->
</FORM>
<SCRIPT><!--
if (frmSearch.handleUser.value == "") {
    frmSearch.handleUser.focus();
} else {
    frmSearch.searchNumber.focus();
}
//--></SCRIPT>

<!--DIV STYLE="width:760px; height:300px; overflow:auto"-->
<DIV STYLE="width:740px; height:300px; overflow:auto">
<!--TABLE WIDTH=740 CELLPADDING=0 BORDER=0 ID=tblOutstanding-->
<TABLE WIDTH=720 CELLPADDING=0 BORDER=0 ID=tblOutstanding name="tblOutstanding"><!--m1-->
  <TR CLASS=cell-label>
    <TD WIDTH=30 ROWSPAN=2></TD>
    <TD WIDTH=80 ROWSPAN=2><%=labelBundle.getString("Customer")%></TD>
    <TD COLSPAN=2><INPUT READONLY CLASS=textbox-label STYLE="width:170px" TABINDEX=-1 VALUE="<%=labelBundle.getString("CustomerName")%>"></TD>
    <TD ALIGN=center><%=labelBundle.getString("NextDD")%></TD>
    <TD ALIGN=center></TD>
    <TD><%=labelBundle.getString("PaymentDetails")%></TD>
    <TD WIDTH=80 ROWSPAN=2 ALIGN=center><%=labelBundle.getString("OutstandingAmount")%><BR>(<%=labelBundle.getString("CreditAmount")%>)</TD>
    <TD WIDTH=80 ROWSPAN=2 ALIGN=center><%=labelBundle.getString("SettleAmount")%></TD>
  </TR>

  <TR CLASS=cell-label>
    <TD WIDTH=90><%=labelBundle.getString("Subscriber")%></TD>
    <TD WIDTH=90><%=labelBundle.getString("InvoiceNumber")%></TD>
    <TD WIDTH=100 ALIGN=center><%=labelBundle.getString("InvoiceDate")%></TD>
    <!--TD WIDTH=60  ALIGN=center><%=labelBundle.getString("Status")%></TD-->
    <TD WIDTH=40  ALIGN=center>Stat</TD>
    <TD WIDTH=130><%=labelBundle.getString("DiscReason")%>/<%=labelBundle.getString("Date")%></TD>
  </TR>
</TABLE>
</DIV>

<TABLE WIDTH=740 CELLPADDING=0>
  <TR>
  <% 
    if (settleBean.isRBD()) {
  %>
    <TD WIDTH=380>&nbsp;</TD>
    <TD WIDTH=60 CLASS=cell-label>&nbsp;</TD>
    <TD WIDTH=80 CLASS=cell-data><input name="printers" type="hidden" value=""></TD>
  <%
    } else {
  %>
    <TD WIDTH=380>&nbsp;</TD>
    <TD WIDTH=60 CLASS=cell-label><%=labelBundle.getString("Printer")%></TD>
    <TD WIDTH=80 CLASS=cell-data>
      <SELECT NAME=printers TABINDEX=10 STYLE="width:70px">
<%
    // Modified by William Tam on 7 Feb 2011, for wireless printers
    //String[] printers = settleBean.getPrinters();
    Printer[] printers = settleBean.getPrinters();
    //String printer = (String) session.getAttribute("SA.Settle.Printer");
    //if (printer == null) printer = settleBean.getDefaultPrinter();
    String printer = SAPrintBean.getDefaultRecPrinter(session, printers);
    if (printers != null) {
        for (int i = 0; i < printers.length; i++) {
%>
        <OPTION VALUE="<%=printers[i].getPrinterName()%>"<%=(printers[i].getPrinterName().equals(printer) ? " SELECTED" : "")%>><%=printers[i].getPrinterName()%></OPTION>
<%
        }
    }
%>
      </SELECT>
    </TD>
<% } %>
    <TD WIDTH=60 CLASS=cell-label><%=labelBundle.getString("Total")%></TD>
    <TD WIDTH=80 CLASS=cell-data>
      <NOBR><INPUT TYPE=Text NAME=outstandingTotal VALUE="0.00" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right"></NOBR>
    </TD>
    <TD WIDTH=80 CLASS=cell-data>
      <NOBR><INPUT TYPE=Text NAME=settleTotal VALUE="0.00" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right"></NOBR>
    </TD>
  </TR>
</TABLE>

<!-- Template table -->
<TABLE STYLE="display:none">
  <TR ID=templateCustomer>
    <TD><INPUT TYPE=Image SRC="/images/btn_deletea.gif" ROLLOVER="../images/btn_delete.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("Remove")%>" HEIGHT=20 TABINDEX=-1 CLASS=image-button ONCLICK="return removeCustomer(this.parentElement.parentElement, 0)"></TD>
    <TD style="text-wrap: nowrap;"><INPUT TYPE=Text NAME=customerNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:65px"><INPUT
               TYPE=Image SRC="/images/btn_browsea.gif" ROLLOVER="/images/btn_browse.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("ViewBillLedger")%>" TABINDEX=-1 CLASS=image-button ONCLICK="if(window.showModalDialog){viewLedger(this.parentElement.children(0).value)}else{viewLedger(this.parentElement.children[0].value)}"><INPUT 
               TYPE=IMAGE SRC="/images/fes/sa/settle/sa-minus.gif" TITLE="<%=labelBundle.getString("ExcludedMobileOrInvoice")%>" TABINDEX=-1 STYLE="vertical-align:middle;cursor:hand;display:none;" ONCLICK="searchExcludedNumber(this.parentElement.parentElement)">
    </TD><!--m1-->    
    <TD COLSPAN=2>
        <INPUT TYPE=Text NAME=customerName VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:170px">
        <SPAN STYLE="display:none; border:solid 1px red; background:darksalmon; color:white; font-size:7pt; font-weight:bold" TITLE="Air-time Pooling">ATP</SPAN>
    </TD>
    <TD ALIGN=center><INPUT TYPE=Text NAME=invoiceDate VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:center"></TD>
    <TD ALIGN=center><INPUT TYPE=Image SRC="/images/btn_arrowa.gif" ROLLOVER="/images/btn_arrow.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("ViewOsInvoices")%>" TABINDEX=-1 CLASS=image-button STYLE="filter:flipv()" ONCLICK="toggleCustomer(this.parentElement.parentElement)"><INPUT
               TYPE=Text NAME=expectedInvoiceCount VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:25px; text-align:center"></TD>
    <TD><INPUT TYPE=Text NAME=paymentMethod VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:20px" MAXLENGTH=1 ONBLUR="return changePaymentCode(this)" HANDLEKEY="uppercase"><INPUT
               TYPE=Text NAME=paymentAmount VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:70px; text-align:right" ONBLUR="return changePaymentAmount(this)" HANDLEKEY="numeric"><INPUT
               TYPE=Image SRC="/images/btn_browsea.gif" ROLLOVER="/images/btn_browse.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("EditPaymentDetails")%>" TABINDEX=-1 CLASS=image-button ONCLICK="return viewPayment(this.parentElement.parentElement)"></TD>
    <TD><INPUT TYPE=Text NAME=outstandingAmount VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right">
        <INPUT TYPE=Text NAME=creditAmount VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="display:none; width:70px; text-align:right"></TD>
    <TD><INPUT TYPE=Text NAME=settleSubtotal VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:70px; text-align:right" ONBLUR="return changeSettleSubtotal(this)" HANDLEKEY="numeric"></TD>
    <!--<TD><INPUT TYPE=Text NAME=settleSubtotal VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right"></TD>-->
  </TR>

  <TR ID=templateInvoice>
    <TD></TD>
    <TD ALIGN=right><!--INPUT TYPE=Text VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:60px"-->
        <INPUT TYPE=image src="/images/envelope.gif" TABINDEX=-1 STYLE="display:none; cursor:hand;">
        <INPUT TYPE=Text VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:60px">
        <INPUT TYPE=Radio TABINDEX=-1 ONCLICK="setSubscriberForPrint(this.name.substring(1), this.value)">
        </TD>
    <TD><INPUT TYPE=Text NAME=subscriberNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px">
        <SPAN STYLE="display:none; border:solid 1px green; background:forestgreen; color:white; font-size:7pt; font-weight:bold" TITLE="Instalment">INST</SPAN>
    </TD>
    <TD><INPUT TYPE=Text NAME=invoiceNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:80px"></TD>
    <TD ALIGN=center><INPUT TYPE=Text NAME=invoiceDate VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:center"></TD>
    <TD ALIGN=center></TD>
    <TD>-</TD>
    <TD><INPUT TYPE=Text NAME=outstandingAmount VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right"></TD>
    <TD><INPUT TYPE=Text NAME=settleAmount VALUE="" MAXLENGTH=10 TABINDEX=9 CLASS=textbox STYLE="width:70px; text-align:right" ONBLUR="return changeSettleAmount(this)" HANDLEKEY="numeric"></TD>
  </TR>

  <TR ID=templatePrepaid>
    <TD><INPUT TYPE=Image SRC="/images/btn_deletea.gif" ROLLOVER="/images/btn_delete.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("Remove")%>" HEIGHT=20 TABINDEX=-1 CLASS=image-button ONCLICK="return removeInvoice(this.parentElement.parentElement)"></TD>
    <TD><INPUT TYPE=Text NAME=customerNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px"></TD>
    <TD><INPUT TYPE=Text NAME=subscriberNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px"></TD>
    <TD><INPUT TYPE=Text NAME=invoiceNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:80px"></TD>
    <TD ALIGN=center><INPUT TYPE=Text NAME=invoiceDate VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:center"></TD>
    <TD ALIGN=center><INPUT TYPE=Image SRC="/images/btn_arrowa.gif" ROLLOVER="/images/btn_arrow.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="View Charges" TABINDEX=-1 CLASS=image-button STYLE="filter:flipv()" ONCLICK="toggleInvoice(this.parentElement.parentElement)"></TD>
    <TD><INPUT TYPE=Text NAME=paymentMethod VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:20px" MAXLENGTH=1 ONBLUR="return changePaymentCode(this)" HANDLEKEY="uppercase"><INPUT
               TYPE=Text NAME=paymentAmount VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:70px; text-align:right" ONBLUR="return changePaymentAmount(this)" HANDLEKEY="numeric"><INPUT
               TYPE=Image SRC="/images/btn_browsea.gif" ROLLOVER="/images/btn_browse.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("EditPaymentDetails")%>" TABINDEX=-1 CLASS=image-button ONCLICK="return viewPayment(this.parentElement.parentElement)"></TD>
    <TD><INPUT TYPE=Text NAME=outstandingAmount VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px; text-align:right"></TD>
    <TD><INPUT TYPE=Text NAME=settleSubtotal VALUE="" TABINDEX=9 CLASS=textbox-read STYLE="width:70px; text-align:right"></TD>
  </TR>

  <TR ID=templateCharge>
    <TD ALIGN=right></TD>
    <TD ALIGN=right>
      <INPUT TYPE=Text VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:60px"><INPUT
             TYPE=Image SRC="/images/btn_deletea.gif" ROLLOVER="/images/btn_delete.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="Remove" HEIGHT=20 TABINDEX=-1 CLASS=image-button ONCLICK="return removeCharge(this.parentElement.parentElement)"></TD>
    <TD COLSPAN=3>
      <INPUT TYPE=Text NAME=chargeType VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:50px"><INPUT
             TYPE=Text NAME=chargeDesc VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:210px">
    </TD>
    <TD ALIGN=center>-</TD>
    <TD>-</TD>
    <TD><INPUT TYPE=Text NAME=chargeAmount VALUE="" TABINDEX=9 CLASS=textbox STYLE="width:70px; text-align:right" ONBLUR="return changeChargeAmount(this)" HANDLEKEY="numeric"></TD>
    <TD></TD>
  </TR>

  <%-- Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260) --%>
  <TR ID=templateNSPSubr>   
    <TD><INPUT TYPE=Image SRC="/images/btn_deletea.gif" ROLLOVER="/images/btn_delete.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("Remove")%>" HEIGHT=20 TABINDEX=-1 CLASS=image-button ONCLICK="return removeError(this.parentElement.parentElement.rowIndex)"></TD>
    <TD><INPUT TYPE=Text NAME=customerNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px"></TD>
    <TD><INPUT TYPE=Text NAME=subscriberNumber VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:70px"></TD>
    <TD COLSPAN=6>
        <INPUT TYPE=Text NAME=message VALUE="" READONLY TABINDEX=-1 CLASS=textbox-read STYLE="width:450px">
        <INPUT TYPE=Image SRC="/images/btn_browsea.gif" ROLLOVER="/images/btn_browse.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("NSPsettlement")%>" TABINDEX=-1 CLASS=image-button ONCLICK="return forwardNSPSettle(this.parentElement.parentElement)">
    </TD>
  </TR>
  <%-- End Added by Billy Pang on 11/01/2019 for enhance cashier settlement interface and workflow - HPP & Mobile (201710110025 , SR0001260) --%>
  <TR ID=templateError>
    <TD><INPUT TYPE=Image SRC="/images/btn_deletea.gif" ROLLOVER="/images/btn_delete.gif" ONFOCUS="ImageHandler.handleMouseOver(this)" ONBLUR="ImageHandler.handleMouseOut(this)" TITLE="<%=labelBundle.getString("Remove")%>" HEIGHT=20 CLASS=image-button ONCLICK="return removeError(this.parentElement.parentElement.rowIndex)"></TD>
    <TD COLSPAN=8></TD>
  </TR>
</TABLE>
</DIV>

<IFRAME ID=fraInvoicing FRAMEBORDER=0
        STYLE="position:absolute; top:0px; left:0px; width:780px; height:550px; visibility:hidden; z-index:1"
></IFRAME><!--m1-->

<DIV ID=divAction STYLE="position:absolute; top:1px; width:1px; height:1px; visibility:hidden"><!-- cannot set to display:none becuase of IE4 incompatibility --></DIV>
<DIV ID=divSave STYLE="position:absolute; top:1px; width:1px; height:1px; visibility:hidden"><!-- cannot set to display:none becuase of IE4 incompatibility -->
  <IFRAME NAME="fraSave" ONREADYSTATECHANGE="saveComplete()"></IFRAME>
  <FORM NAME="frmSave" id="frmSave" TARGET="fraSave" ACTION="/servlet/fes.sa.settle.SASettleSaveServlet" METHOD="Post"
  ></FORM>
</DIV>

</BODY>
</HTML>
