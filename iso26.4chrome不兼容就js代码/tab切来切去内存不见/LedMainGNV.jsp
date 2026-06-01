<%@ page pageEncoding="utf-8" language="java"
	import="java.sql.*,java.text.*,java.math.*,java.util.*,fes.helper.*,fes.cs.*,fes.cs.cams.*,fes.cs.loginNow.*,fes.cs.custEnq.*,fes.sa.kiosk.KioskOutstanding, fes.cs.NewBilling.*, org.json.*"
	contentType="text/html;charset=utf-8"
%>
<%
    Locale locale = (Locale) session.getAttribute("locale");   
    ResourceBundle lbl_bundle = ResourceBundle.getBundle("fes/cs/ledger/label",locale);
    String subr_num = StringHelper.trim(request.getParameter("subscriberNumber"));  
    String cust_num = StringHelper.trim(request.getParameter("customerNumber"));  
    String account_num = StringHelper.trim(request.getParameter("accountNumber"));  
    String nspCustNum = "";
    String nspAccountNum = "";
    String idbr = StringHelper.trim(request.getParameter("idbr"));
    String proactive = StringHelper.trim(request.getParameter("proactive"));
    String custrent = StringHelper.trim(request.getParameter("custrent"));
    char billingSource = 'P';
    String custNumTemp = "";
    String accountNoTemp = "";
    boolean isPrepaid = false;
    //add by allen for test shopNSave page
    String fromShopNSave = StringHelper.trim(request.getParameter("fromShopNSave"));
    String isShopNSave = "";
    
    Integer userid;
    int user_id = Integer.parseInt(request.getParameter("userid")==null?"0":request.getParameter("userid")); 
    if (user_id == 0) {
        userid = (Integer) session.getAttribute("FES.userid");	 // Add by Frank at 11-02-2003 for contact management
    } else {
        userid = new Integer(user_id);
    }
    int log_user_id = ((Integer)session.getAttribute("FES.userid")).intValue();
    Integer salesid = (Integer) session.getAttribute("FES.salesid");
    int sid = 0;
    
    String accessrights = (String)session.getAttribute("FES.accessrights");
    //session.setAttribute("FES.isConnected", "Y");
    boolean dimmedButton = CDRAccessHandler.dimmedButton(account_num);                  

    ConnectionPool pool = null;
    ConnectionFactory pool2 = FesConnectionFactory.getInstance();   
    Connection conn = null;
    Connection conn2 = null;
    ResultSet rset = null;
    PreparedStatement pstmt = null;
    CallableStatement cstmt = null;
    String sql = "";

    boolean isRestrictedRBD = Contain.checkcode(accessrights, "C44");   // Restricted to access certain BM customer information
    boolean displayCDRAccessBtn = Contain.checkcode(accessrights, "CC7");
    boolean isHigherBMCustomer = CSToolkit.isHigherBMCustomer(cust_num, subr_num, idbr);

    BigDecimal ositem = new BigDecimal("0.00");
    BigDecimal f0t19 = new BigDecimal("0.00");
    BigDecimal f20t29 = new BigDecimal("0.00");
    BigDecimal f30t59 = new BigDecimal("0.00");
    BigDecimal f60t89 = new BigDecimal("0.00");
    BigDecimal f90 = new BigDecimal("0.00");
    BigDecimal rechargeThisMonth = new BigDecimal("0.00");

    String billdate = "";
    String tx_date = "";
    String sled_ref = "";
    String tx_type = "";
    String tx_ref = "";
    String sled_amount = "";
    String sled_balance = "";
    String first_allc_date = "";
    String comp_allc_date = "";
    String ac_num = "";
    String pay_type = "";
    String tmp = "";
    String tmpyr = ""; 
    String invoice = "";
    String kioskCustBalance = "";

    // New Billing Integration - Begin - Expiry Date = fesrb_sa_subr_info_view.card_status_timeout_date
    /*
    SubrStatusHandler subrStatusHandler = new SubrStatusHandler(subr_num, cust_num, account_num);
    SubrStatusHandler.SubrStatus subrStatus = subrStatusHandler.getSubrStatus();    
    SimpleDateFormat sdfDMY = new SimpleDateFormat("dd-MM-yyyy HH:mm");
    */
    String prepaidExpiryDate = "";
    // New Billing Integration - End - Expiry Date = fesrb_sa_subr_info_view.card_status_timeout_date
    try {
        pool = ConnectionPool.getInstance();
        conn = pool.getConnection();
        conn2 = pool2.getConnection();  
           
        if (cust_num.equals("") && subr_num.equals("")) {
            SessionData sd = new SessionData(session);
            cust_num = sd.getCurrentCustomerNumber();
            subr_num = sd.getCurrentSubscriberNumber();
            account_num = sd.getCurrentAccountNumber();
            idbr = sd.getCurrentIdbr();
            isPrepaid = "prepaid".equals(sd.getCurrentSubscriptionStyle());
        } else {
            String subrStyle = CSToolkit.getSubrStyle(subr_num, cust_num);
            if(StringHelper.isEmpty(subrStyle)){
                pstmt = conn.prepareStatement("SELECT lib_customer_postpaid.check_cust_num(?) FROM dual");
                pstmt.setString(1, cust_num);
                rset = pstmt.executeQuery();
                if (rset.next()) {                 
                    subrStyle = StringHelper.trim(rset.getString(1));
                }
                isPrepaid = !"Y".equals(subrStyle);
                rset.close();
                pstmt.close();
            } else {
                isPrepaid = "prepaid".equals(subrStyle);
            }
            
            pstmt = conn.prepareStatement(
                "SELECT account_num, hkid_br FROM " + (isPrepaid ? "fesrb_" : "") + "sa_subr_info_view " +
                " WHERE cust_num = ? AND subr_num = ? " +
                " ORDER BY subr_sw_off DESC NULLS FIRST "
            );
            pstmt.setString(1, cust_num);
            pstmt.setString(2, subr_num);
            rset = pstmt.executeQuery();
            if (rset.next()) {
                account_num = rset.getString(1);
                idbr = rset.getString(2);
            }
            rset.close();
            pstmt.close();
        }            
        
        billingSource = CSToolkit.getBillingSource(subr_num,cust_num);

        // New Billing Integration - Begin - Expiry Date = fesrb_sa_subr_info_view.card_status_timeout_date
        if(isPrepaid) {
            // String prepaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Prepaid_Enquiry();
            // if("0".equals(prepaidParallelRun)) {
                SubrStatusHandler subrStatusHandler = new SubrStatusHandler(subr_num, cust_num, account_num);
                SubrStatusHandler.SubrStatus subrStatus = subrStatusHandler.getSubrStatus();    
                SimpleDateFormat sdfDMY = new SimpleDateFormat("dd-MM-yyyy HH:mm");
                prepaidExpiryDate = (subrStatus.getExpiryDate()==null?"--":sdfDMY.format(subrStatus.getExpiryDate()));
            // } else { // for New Billing
            //     prepaidExpiryDate = fes.cs.ledger.LedMainGNVHandler.getPrepaidExpiryDate(conn, cust_num, subr_num);
            // }
        }
        // New Billing Integration - End - Expiry Date = fesrb_sa_subr_info_view.card_status_timeout_date
        
        char getLineCategory = CSToolkit.getLineCategory(subr_num, cust_num, account_num);
        if(getLineCategory == 'F' || getLineCategory == 'X' || getLineCategory == 'R') {
            pstmt = conn.prepareStatement(
                "SELECT nsp_cust_num, nsp_account_num FROM fesrb_sa_fl_info_view WHERE cust_num = ? AND subr_num = ? "
            );
            //NB -- End
            pstmt.setString(1, cust_num);
            pstmt.setString(2, subr_num);
            rset = pstmt.executeQuery();
            if (rset.next()) {
                nspCustNum = rset.getString(1);
                nspAccountNum = rset.getString(2);
            }
            rset.close();
            pstmt.close();
        }
        boolean isNspHpp = !"".equals(nspCustNum);
        custNumTemp = isNspHpp ? nspCustNum : cust_num;
        accountNoTemp = isNspHpp ? nspAccountNum : account_num;

        if (cust_num.length() > 0) {
            // get ShopNSave flag
            isShopNSave = NBCommon.isShopNSaveByMapSubrNum(conn, subr_num) ? "Y" : "N";
            
            sql = "select lib_customer_" + (isPrepaid || billingSource == 'F' ? "prepaid" : "postpaid") + ".get_bill_day(?) from dual";
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, billingSource == 'F' ? nspCustNum : cust_num);
            rset = pstmt.executeQuery();
            if (rset.next()) {
                billdate = StringHelper.trim(rset.getString(1));
            }
            if("-1".equals(billdate)) {
                billdate = "--";
            }
            rset.close();
            pstmt.close();

            if (isPrepaid) {
                //NB -- Begin, use newbilling ICP api transactionHistory to replace the old GNV table(fesrb_accountpayment, fesrb_accountattributes)
                String prepaidParallelRun = NBConfigUtil.getPostSalesParallelRun_Prepaid_Enquiry();
                if("0".equals(prepaidParallelRun)) {
                    pstmt = conn.prepareStatement(
                        "select nvl(sum(ap.account_payment_mny)/1000, 0) " +
                        "  from fesrb_accountpayment ap, fesrb_accountattributes aa " +
                        " where aa.account_num = ap.account_num " +
                        "   and ap.account_payment_status = 1 " +
                        "   and ap.payment_balance_type = 101 " +
                        "   and ap.account_payment_dat between to_date(aa.last_charge_date, 'dd/mm/yyyy') and to_date(aa.next_charge_date, 'dd/mm/yyyy') " +
                        "   and ap.account_payment_txt not in ('FES', 'Balance Migration') " +
                        "   and ap.account_num = ? " +
                        "   and to_date(aa.next_charge_date, 'dd/mm/yyyy') >= trunc(sysdate) "
                    );
                    pstmt.setString(1, accountNoTemp);
                    rset = pstmt.executeQuery();
                    if (rset.next()) {
                        rechargeThisMonth = rset.getBigDecimal(1);
                    } 
                    rset.close();
                    pstmt.close();
                }else{
                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
                    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    String parallelRun = NBConfigUtil.getParallelRun("ICP_NBILL_BRM.parallelRun.transactionHistory");
                    String endDate = fes.cs.ledger.LedMainGNVHandler.getPrepaidEndDate(conn, null);
                    String startDate = fes.cs.ledger.LedMainGNVHandler.getPrepaidStartDate(conn, endDate);
                    JSONObject tDataParams = new JSONObject();
                    JSONObject aDataParams = new JSONObject();
                    tDataParams.put("accountNum", accountNoTemp);
                    if("Y".equals(isShopNSave)) {
                        tDataParams.put("custNum", custNumTemp);
                        tDataParams.put("subrNum", subr_num);
                    }
                    tDataParams.put("startDate",formatter.format(inputFormat.parse(startDate)));
                    tDataParams.put("endDate",formatter.format(inputFormat.parse(endDate)));
                    tDataParams.put("parallelRun", parallelRun);
                    tDataParams.put("isShopNSave", isShopNSave);

                    aDataParams.put("accountNum",accountNoTemp);
                    aDataParams.put("startDate",startDate);
                    aDataParams.put("endDate",endDate);
                    aDataParams.put("parallelRun",parallelRun);
                    JSONObject transactionHistory = CallApiHandler.getTransactionHistory(tDataParams,"",subr_num,accountNoTemp,parallelRun);
                    //JSONObject adjustmentHistory = CallApiHandler.getAdjustmentHistory(aDataParams,"",subr_num,accountNoTemp,parallelRun);
                //    JSONArray paymentInfo = transactionHistory.optJSONArray("paymentInfo");
                //    if(paymentInfo != null){
                //        for(int i = 0; i < paymentInfo.length(); i++){
                //            JSONObject paymentInfoObj = paymentInfo.optJSONObject(i);
                //            try {
                //                BigDecimal amountInfo = new BigDecimal(StringHelper.trim(paymentInfoObj.getString("amount")));
                //                rechargeThisMonth = rechargeThisMonth.add(amountInfo);
                //            } catch (Exception e) {
                //                e.printStackTrace(System.err);
                //            }
                //        }
                //        rechargeThisMonth = rechargeThisMonth.abs();
                    try {
                        BigDecimal transactionTotalAmount = new BigDecimal(StringHelper.trim(transactionHistory.optString("rechargeAmtThisMth", "0")));
                        //BigDecimal adjustmentTotalAmount = new BigDecimal(StringHelper.trim(adjustmentHistory.optString("totalAmount", "0")));
                        //rechargeThisMonth = transactionTotalAmount.add(adjustmentTotalAmount);
                        rechargeThisMonth = transactionTotalAmount;
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                }
                //NB -- End
            } else {
                sql = "{call cs_bill_ledger.gnv_bill_ldg_bal_cal(?,?,?,?,?,?,?)}";
                cstmt= conn.prepareCall(sql);
                cstmt.setString(1, cust_num);
                cstmt.registerOutParameter(2,java.sql.Types.INTEGER);
                cstmt.registerOutParameter(3,java.sql.Types.INTEGER);
                cstmt.registerOutParameter(4,java.sql.Types.INTEGER);
                cstmt.registerOutParameter(5,java.sql.Types.INTEGER);
                cstmt.registerOutParameter(6,java.sql.Types.INTEGER);
                cstmt.registerOutParameter(7,java.sql.Types.INTEGER);
                cstmt.executeUpdate();
                ositem = ositem.add(cstmt.getBigDecimal(2));
                f0t19 = f0t19.add(cstmt.getBigDecimal(3));
                f20t29 = f20t29.add(cstmt.getBigDecimal(4));
                f30t59 = f30t59.add(cstmt.getBigDecimal(5));
                f60t89 = f60t89.add(cstmt.getBigDecimal(6));
                f90 = f90.add(cstmt.getBigDecimal(7));
                
                if (billingSource == 'F') {
                    sql = "{call cs_bill_ledger.nsp_bill_ldg_bal_cal(?,?,?,?,?,?,?)}";
                    cstmt = conn2.prepareCall(sql);
                    cstmt.setString(1, custNumTemp);
                    cstmt.registerOutParameter(2,java.sql.Types.INTEGER);
                    cstmt.registerOutParameter(3,java.sql.Types.INTEGER);
                    cstmt.registerOutParameter(4,java.sql.Types.INTEGER);
                    cstmt.registerOutParameter(5,java.sql.Types.INTEGER);
                    cstmt.registerOutParameter(6,java.sql.Types.INTEGER);
                    cstmt.registerOutParameter(7,java.sql.Types.INTEGER);
                    cstmt.executeUpdate();
                    ositem = ositem.add(cstmt.getBigDecimal(2));
                    f0t19 = f0t19.add(cstmt.getBigDecimal(3));
                    f20t29 = f20t29.add(cstmt.getBigDecimal(4));
                    f30t59 = f30t59.add(cstmt.getBigDecimal(5));
                    f60t89 = f60t89.add(cstmt.getBigDecimal(6));
                    f90 = f90.add(cstmt.getBigDecimal(7));
                }
            }
            // New Billing Integration - Begin - call /account/KioskBalanceByCust
            // try {
            //     KioskOutstanding kiosk = new KioskOutstanding(conn, cust_num, subr_num);
            //     kioskCustBalance = kiosk.getCustAmountString();
            // } catch (Exception e) {
            //     System.out.println("fes.sa.kiosk.KioskOutstanding ERROR: " + e.getMessage());
            // }
            if(isPrepaid || billingSource == 'F') {
                String prepaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Prepaid_Enquiry();
                if("0".equals(prepaidParallelRun)) {
                    try {
                        KioskOutstanding kiosk = new KioskOutstanding(conn, cust_num, subr_num);
                        kioskCustBalance = kiosk.getCustAmountString();
                    } catch (Exception e) {
                        System.out.println("fes.sa.kiosk.KioskOutstanding ERROR: " + e.getMessage());
                    }
                } else { // for New Billing
                    kioskCustBalance = fes.cs.ledger.LedMainGNVHandler.getKioskBalanceByCust(cust_num, subr_num, accountNoTemp, isShopNSave);
                }
            } else {
                String postpaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Postpaid_Enquiry();
                if("0".equals(postpaidParallelRun) || "2".equals(postpaidParallelRun)) {
                    try {
                        KioskOutstanding kiosk = new KioskOutstanding(conn, cust_num, subr_num);
                        kioskCustBalance = kiosk.getCustAmountString();
                    } catch (Exception e) {
                        System.out.println("fes.sa.kiosk.KioskOutstanding ERROR: " + e.getMessage());
                    }
                } else { // for New Billing
                    kioskCustBalance = fes.cs.ledger.LedMainGNVHandler.getKioskBalanceByCust(cust_num, subr_num, accountNoTemp, isShopNSave);
                }
            }
            // New Billing Integration - End - call /account/KioskBalanceByCust
        }
%>
<!DOCTYPE HTML>
<html>
<head>
<title>Front End System Online</title>
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta http-equiv="Content-Type" content="text/html; charset=utf-8;">
<link rel="stylesheet" href="/jsp/pos5.css" type="text/css">
<script type="text/javascript" src="/lib/jquery-1.8.2.min.js"></script>
<link href="/lib/jquery-plugins/jquery-ui-1.9.2/css/redmond/jquery-ui-1.9.2.custom.css" rel="stylesheet">    
<script src="/lib/jquery-plugins/jquery-ui-1.9.2/jquery-ui-1.9.2.custom.min.js"></script>
<script type="text/javascript" src="/jsp/Script5.jsp"></script>
<script language="javascript">
/*
var gnvRecordGroup = new Array();   // for storing GnvRecordGroup objects
var AddGNVTimeOut = null;           // for controlling start/stop adding GNV records from cache
*/
var other_win_ebill = [];
var other_win = [];
var smallwin = [];
// GnvRecordGroup Object - the table rows (many GnvRecord objects) under current (Existing/History) SubrNum
/*
function GnvRecordGroup() {
    this.gnvRecords = new Array();  // Storing GnvRecord objects
    
    this.loadCompleted = false;
    this.addGnvRecord = function(newRecord) {this.gnvRecords.push(newRecord);}
}*/

// GnvRecord Object - each object is each row displayed in the table
function GnvRecord(tx_date, tx_ref, tx_type, pay_type, sled_amount, sled_balance, 
                   sled_ref, first_allc_date, comp_allc_date, ac_num, hasBillImgDtl) {
    this.tx_date = tx_date;
    this.tx_ref = tx_ref;
    this.tx_type = tx_type;
    this.pay_type = pay_type;
    this.sled_amount = sled_amount;
    this.sled_balance = sled_balance;
    this.sled_ref = sled_ref;
    this.first_allc_date = first_allc_date;
    this.comp_allc_date = comp_allc_date;
    this.ac_num = ac_num;
    this.hasBillImgDtl = hasBillImgDtl;
}

function init() {
<%      if (isRestrictedRBD && isHigherBMCustomer) {
            //String[] accountManagers = CSToolkit.getCAMSAccountManagers(idbr, cust_num, subr_num);
            String[] accountManagers = AccountInfo.findManagers(idbr, cust_num, subr_num);
            if (!accountManagers[1].equals("")) {  %>
                alert('<%=MessageFormat.format(lbl_bundle.getString("This_information_is_not_shown_for_this_BM_customer_2"), new Object[] {accountManagers[0], accountManagers[1]})%>');
<%          } else {  %>
                alert('<%=MessageFormat.format(lbl_bundle.getString("This_information_is_not_shown_for_this_BM_customer_1"), new Object[] {accountManagers[0]})%>');
<%          }
        } else if (!cust_num.equals("")) {  %>
            getExistingRecords();
<%      } %>
}

function getExistingRecords() {
    //  Stop loading records from iframe (if loading)
    document.getElementById("GNVRecordIFrame").src = "";
    //  Stop loading records from cache (if loading)
    //clearTimeout(AddGNVTimeOut);
    
    // Remove existing table contents
    var gnvTable = document.getElementById("GNVRecordTable"); 
    while (gnvTable.rows.length > 1) {
        gnvTable.deleteRow(gnvTable.rows.length - 1);   // Delete all records except header
    }
    
    // Load existing records
    loadGNVRecord(false);
}

function getHistoryRecords() {
    // Remove "Show History" Button
/*    
    var gnvTable = document.getElementById("GNVRecordTable");
    gnvTable.deleteRow(gnvTable.rows.length - 1);
*/
    var gnvTable = document.getElementById("MessageTable"); 
    for (var i = gnvTable.rows.length - 1; i >= 0; i--) {
        gnvTable.deleteRow(i);
    }
  

    // Load and append to existing records
    loadGNVRecord(true);
}

function loadGNVRecord(getHistory) {
    addMessage("<%=lbl_bundle.getString("PleaseWaitWhileLoading")%>");
    // Load records from iframe
    // New Billing Integration - Begin
    <%
        char lineCategory = CSToolkit.getLineCategory(subr_num, cust_num, account_num);
        String contentJSP = "";
        if(isPrepaid) {
            contentJSP = "LedMainGNVContentPrepaid.jsp";
            String prepaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Prepaid_Enquiry();
            if(!"0".equals(prepaidParallelRun) && !(lineCategory == 'F' || lineCategory == 'X' || lineCategory == 'R')) { // for New Billing
                contentJSP = "LedMainGNVContentPrepaid_NB.jsp";
            }
        } else if(isNspHpp) {

            contentJSP = "LedMainGNVContentNspHpp.jsp";
            String prepaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Prepaid_Enquiry();
            if(!"0".equals(prepaidParallelRun) && !(lineCategory == 'F' || lineCategory == 'X' || lineCategory == 'R')) { // for New Billing
                contentJSP = "LedMainGNVContentNspHpp_NB.jsp";
            }
        } else {
            contentJSP = "LedMainGNVContent.jsp";
            String postpaidParallelRun = fes.cs.NewBilling.NBConfigUtil.getPostSalesParallelRun_Postpaid_Enquiry();
            if(!("0".equals(postpaidParallelRun) || "2".equals(postpaidParallelRun)) && !(lineCategory == 'F' || lineCategory == 'X' || lineCategory == 'R')) { // for New Billing
                contentJSP = "LedMainGNVContent_NB.jsp";
            }
        }
        System.out.println("LedMainGNV.jsp - loadGNVRecord - contentJSP="+contentJSP);
    %>
    // genContentUrl = "/jsp/fes/cs/ledger/LedMainGNVContent<%= isPrepaid ? "Prepaid" : (isNspHpp ? "NspHpp" : "") %>.jsp" +
    genContentUrl = "/jsp/fes/cs/ledger/<%=contentJSP %>" +
                    "?subrNum=<%= subr_num %>" +
                    "&custNum=<%= cust_num %>" +
                    "&nspCustNum=<%= nspCustNum %>" +
                    "&accountNum=<%= account_num %>" +
                    "&nspAccountNum=<%= nspAccountNum %>" +
                    "&getHistory=" + getHistory;    // true / false
    // New Billing Integration - Begin
    document.getElementById("GNVRecordIFrame").src = genContentUrl;
}

function addHTML(html) {
    html = "<table border=0 width=1060 cellspacing=1 cellpadding=1 vspace=1 hspace=1 style='table-layout:fixed'>" + 
           "<col width=30><col width=40><col width=95><col width=150><col width=80><col width=70><col width=65><col width=185><col width=85><col width=130><col width=120>" +
           html + "</" + "table>";
    document.getElementById("GNVRecordTable").insertAdjacentHTML("BeforeBegin", html);
}

function addNspHppHTML(html) {
    html = "<table border=0 width=800 " + 
           "       cellspacing=1 cellpadding=1 style='table-layout:fixed'>" + 
           "<col width=30><col width=40><col width=60><col width=100><col width=140><col width=70><col width=140><col width=85>" +
           html + "</" + "table>";
    document.getElementById("GNVRecordTable").insertAdjacentHTML("BeforeBegin", html);
}

function addPrepaidHTML(html, hasBill) {
    html = "<table border=0 width=800 " + 
           "       cellspacing=1 cellpadding=1 style='table-layout:fixed'>" + 
           "<col width=60><col width=140><col width=80><col width=70><col width=170><col width=85>" +
           html + "</" + "table>";
    document.getElementById("GNVRecordTable").insertAdjacentHTML("BeforeBegin", html);
}

function addHistoryButton() {   // Add "Show History" button to last row
    var HistoryButton = "<button type='button' class='thin-button' onClick=\"getHistoryRecords();\">Show History<//button>"
    addMessage(HistoryButton);
}

function checkNoRecordCase(hasRecord) {
    if (!hasRecord) addMessage("No Record found.");
}

function addMessage(message) {
    var gnvTable = document.getElementById("MessageTable"); 
    newRow = gnvTable.insertRow(gnvTable.rows.length);
    newRow.className = "cell-data";
    var historyButtonField = newRow.insertCell(0);
    //historyButtonField.colSpan = 11;
    historyButtonField.innerHTML = message;
}

function deleteMessage(message) {
    var gnvTable = document.getElementById("MessageTable"); 
    for (var i = gnvTable.rows.length - 1; i >= 0; i--) {
        if (gnvTable.rows[i].innerText == message) {
            gnvTable.deleteRow(i);
        }
    }
}

function viewEbill(index, sled_ref, tx_ref, in_date) {
    var features="toolbar=no,scrollbars=yes,WIDTH=780,HEIGHT=560,left=0,top=0";
    // other_win_ebill[index]=window.open('/servlet/fes.jupiter.ebill.LedBillGNV?finalB=ledMAIN&billingSource=<%=billingSource%>&customerNumber=<%=cust_num%>&subscriberNumber=<%=billingSource == 'F' ? "' + tx_ref + '" : ""%>&sledRef=' + sled_ref + '&billDate='+in_date,'ebill'+index,features);
    other_win_ebill[index]=window.open('/servlet/fes.jupiter.ebill.LedBillGNV?finalB=ledMAIN&billingSource=<%=billingSource%>&customerNumber=<%=cust_num%>&subscriberNumber=<%=billingSource == 'F' ? "' + tx_ref + '" : ""%>&sledRef=' + sled_ref + '&billDate='+in_date,'viewEbillWindow',features);
    other_win_ebill[index].focus();
    other_win_flag=1;
    <%--//window.open('/jsp/fes/cs/CSLogging.jsp?CustNum=<%=log_cust_num%>&SubrNum=<%=log_subr_num%>&ActType=EBILL&uptype=y','log','toolbar=no,scrollbars=no,WIDTH=10,HEIGHT=10,left=0,top=0');--%>
}

function openWindow(url, windowID, features) {                
    window.open(url, windowID, features);
}

</script>
</head>
<%--Modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix--%>
<%--<body bgcolor="#ffffff" onload="init();" onunload=closewin()>--%>
<body bgcolor="#ffffff" onload="init();">
<%--End modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix--%>
<%	if(proactive.equals("pro") || custrent.equals("cr")) { %>
<div STYLE=' HEIGHT: 100px; LEFT: 280px; POSITION: absolute; TOP: 0px; VISIBILITY: hidden; WIDTH: 200px; z-index:3' id=butLayer>
<%	} else {
%>
<div STYLE=" HEIGHT: 100px; LEFT: 380px; POSITION: absolute; TOP: 0px; VISIBILITY: hidden; WIDTH: 200px; z-index:3" id=butLayer>
<%	}	%>

<%      if (!isPrepaid) {%>
<table bgColor=#000000 border=0 cellPadding=1 cellSpacing=1 width=100% style="HEIGHT: 100px; WIDTH: 200px" borderColor  =mediumblue height=20>
<tr>
<td class=cell-label><%=lbl_bundle.getString("Current")%>:</td>
<td class=cell-data><%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : f0t19.toString() %></td>
</tr>
<tr>
<td class=cell-label><%=lbl_bundle.getString("20_Days")%>:</td>
<td class=cell-data><%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : f20t29.toString() %></td>
</tr>
<tr>
<td class=cell-label><%=lbl_bundle.getString("30_Days")%>:</td>
<td class=cell-data><%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : f30t59.toString() %></td>
</tr>
<tr>
<td class=cell-label><%=lbl_bundle.getString("60_Days")%>:</td>
<td class=cell-data><%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : f60t89.toString() %></td>
</tr>
<tr>
<td class=cell-label><%=lbl_bundle.getString("90_Days")%>:</td>
<td class=cell-data><%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : f90.toString() %></td>
</tr>
</table>
<%      } %>
</div>
<!-- <div id="L0" style="position:absolute; left:0px; top:0px; width:100%; min-width:750px; z-index:1; overflow: none">  -->
<div id="L0" style="position:absolute; left:0px; top:0px; width:100%; min-width:460px; z-index:1; overflow: none"> <!-- 2024.1.5 Chrome compatibility width (BensonLee) -->
<%
	if (proactive.equals("pro")) { // call from pr
%>
<a href='/servlet/fes.cr.CROffHist?proactive=pro'><img src='<%=lbl_bundle.getString("tab_pr_retenta")%>' width='50' border='0'></a>
<img src='<%=lbl_bundle.getString("tab_pr_ledger")%>' width='50' border='0'>
<a href='/servlet/fes.cs.creditback.CrBackDetailsHdr?proactive=pro'><img src='<%=lbl_bundle.getString("tab_pr_cba")%>' width='50' border='0'></a>
<a href='/jsp/fes/cs/vrs/VasExistMain.jsp?proactive=pro'><img src='<%=lbl_bundle.getString("tab_pr_vasa")%>' width='50' border='0'></a>
<a href='/jsp/fes/cs/relatedSubs/CSRelSubs.jsp?proactive=pro'><img src='<%=lbl_bundle.getString("tab_pr_suba")%>' width='50' border='0'></a>
<a href='/servlet/fes.cs.callProfile.CallProfile?proactive=pro'><img src='<%=lbl_bundle.getString("tab_pr_calla")%>' width='50' border='0'></a>
<%	} else if (custrent.equals("cr")) { //call from cr
%>
<a href='/servlet/fes.cr.CROffHist?custrent=cr'><img src='<%=lbl_bundle.getString("tab_cr_retent")%>' width='50' border='0'></a>
<img src='<%=lbl_bundle.getString("tab_cr_ledgera")%>' width='50' border='0'>
<a href='/servlet/fes.cs.creditback.CrBackDetailsHdr?custrent=cr'><img src='<%=lbl_bundle.getString("tab_cr_cb")%>' width='50' border='0'></a>
<a href='/jsp/fes/cs/vrs/VasExistMain.jsp?custrent=cr'><img src='<%=lbl_bundle.getString("tab_cr_vas")%>' width='50' border='0'></a>
<a href='/jsp/fes/cs/relatedSubs/CSRelSubs.jsp?custrent=cr'><img src='<%=lbl_bundle.getString("tab_cr_sub")%>' width='50' border='0'></a>
<a href='/servlet/fes.cs.callProfile.CallProfile?custrent=cr'><img src='<%=lbl_bundle.getString("tab_cr_call")%>' width='50' border='0'></a>
<%
	}
	if (proactive.equals("pro") || custrent.equals("cr")) {
%>
<!--<table border='0' width='440' cellspacing='2' cellpadding='1' vspace='1' hspace='1'>-->
<table border='0' cellspacing='2' cellpadding='1' vspace='1' hspace='1' style="width:90%; min-width:440px; max-width:740px">
<%	
	} else {
%>
<% if(!"Y".equals(fromShopNSave)){  %>
<table border="0" width="700" cellspacing="2" cellpadding="1" vspace="1" hspace="1">
  <col width=500>
  <col width=200>
<tr>
<td>
<font class=subTitle><%=lbl_bundle.getString("Geneva_Ledger")%></font>
</td>
<td>
<img src="/images/logo.gif" width="141" height="58">
</td>
</tr>
<tr>
    <td>
        <img src="<%=lbl_bundle.getString("bar")%>" width="250" height="19"><img 
             src="<%=lbl_bundle.getString("tab_jup_detailsa")%>" width="116" height="19"><a 
             href="DepDetailGNV.jsp?cust_num=<%=cust_num%>&subr_num=<%=subr_num%>&nsp_cust_num=<%=nspCustNum%>"><img 
             src="<%=lbl_bundle.getString("tab_deposit_details")%>" width="116" height="19" border="0"></a> 
    </td>
    <td>
          <% if (displayCDRAccessBtn) {%>
          <button type=button class=button onclick="<%=CDRAccessHandler.retrieveCDRAccessURL()%>" <%=dimmedButton?"disabled":""%>>CDR Access</button>
          <% } %>
          <button type=button class=button onclick="window.close()">Close</button>
    </td>
</tr>
</table>
<% } %>


<%
//add by allen 20250701
//if("Y".equals(fromShopNSave)){ 
%>
    <%-- <table border="0" width="850" cellspacing="2" cellpadding="1" vspace="1" hspace="1"> --%>
<%//}else{ %>
    <table border="0" width="740" cellspacing="2" cellpadding="1" vspace="1" hspace="1">
<%//} %>
<%	}
  if (!isPrepaid) {
%>
  <!--<col width=54>
  <col width=40>
  <col width=200>
  <col width=75>
  <col width=100>
  <col width=75>
  <col width=80>
  <col width=75>-->
  <col style="width:7%; min-width:26px;">
  <col style="width:5%; min-width:19px;">
  <col style="width:25%; min-width:95px;">
  <col style="width:9%; min-width:34px;">
  <col style="width:13%; min-width:47px;">
  <col style="width:9%; min-width:34px;">
  <col style="width:10%; min-width:38px;">
  <col style="width:9%; min-width:34px;">
<% } else {%>
  <col width=64>
  <col width=62>
  <col width=80>
  <col width=150>
  <col width=250>
  <col width=80>
<% }%>
  <tr> 
    <td class=cell-label>Bill Day&nbsp;</td>
    <td class=cell-data> 
      <div align="CENTER"><%=billdate%></div>
    </td>
    <%if (!isPrepaid) {%>
    <td class=cell-label><%=lbl_bundle.getString("account_bal")%> <font size=1>(<%=lbl_bundle.getString("incl_unbill_dbt_adj")%>)</font>&nbsp</td>
    <td class=cell-data> 
      <div align="CENTER" onmouseover="Javascript:butLayer.style.visibility='visible';" onmouseout="Javascript:butLayer.style.visibility='hidden';">
        <%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : ositem.toString() %>
      </div>
    </td>
    <td class=cell-label>Kiosk O/S (a/c)</td>
    <td class=cell-data>
      <div align="CENTER">
        <%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : kioskCustBalance %>
      </div>
    </td>
    <td class=cell-label><%=lbl_bundle.getString("os_balance")%>&nbsp</td>
    <td class=cell-data> 
      <div align="CENTER">
        <%= (isRestrictedRBD && isHigherBMCustomer) ? "-" : (ositem.signum() >= 0 ? ositem.toString() : "0.00") %>
      </div>
    </td>
    <%} else {%>    
    <td class=cell-label><%= lbl_bundle.getString("expiry_date") %>&nbsp</td>
    <td class=cell-data> 
        <div align="CENTER"><%=prepaidExpiryDate%></div>
    </td>
    <td class=cell-label>Successful Recharge for this Month</td>
    <td class=cell-data><div align="CENTER">$<%= rechargeThisMonth %></div></td>
    <%}%>
    <td>
        <IFRAME name=GNVRecordIFrame id=GNVRecordIFrame src="" onreadystatechange=""
                width=0 height=0 frameborder=0 marginwidth=0 marginheight=0 scrolling=no
                style="position:absolute; left:660px; top:66px; width:90px; height:29px;">
                <!-- Display the "Loading..." Label -->
        </IFRAME>
    </td>
  </tr>
</table>

<!--<div id="Layer1" style="position:absolute; left:0px; <%= (proactive.equals("pro") || custrent.equals("cr")) ? (proactive.equals("pro")? "top:80px; width:460px; height:430px " : "top:80px; width:460px; height:220px ") : "top:130px; width:760px; height:430px" %>; z-index:1; overflow: auto;">
-->
<% if("Y".equals(fromShopNSave)){ %>
    <div id="Layer1" style="position:relative; left:0px; top:0px; width:740px; min-width:460px; height:450px; z-index:1; overflow: auto;">
<% }else{ %>
    <div id="Layer1" style="position:absolute; left:0px; <%= (proactive.equals("pro") || custrent.equals("cr")) ? (proactive.equals("pro")? "top:80px; width:460px; height:430px " : "top:80px; width:100%; min-width:460px; height:220px ") : "top:130px; width:760px; height:430px" %>; z-index:1; overflow: auto;"> <!-- 2024.1.5 Chrome compatibility (BensonLee) -->
<% } %>
<script language=JavaScript>
var	other_win_flag=0;
var	ebill_flag = 0;
function openwin(index,inv,inv_date,ref,tot){
	var features="scrollbars=yes,left=300,top=0,width=520,height=550";
	//closewin();
	other_win[index]=window.open("/servlet/fes.cs.ledger.Invoice?reference="+ref+"&inv_num="+inv+"&inv_date="+inv_date+"&customerNumber=<%=cust_num%>&inv_tot="+tot+"&index="+index,"openwin"+index,features);
	other_win[index].focus();
        other_win_flag=1;
}


function openSmallWindow(url,index2) {
    var features="scrollbars=yes,left=300,top=0,width=520,height=550";
    if(smallwin[index2] != null && !smallwin[index2].closed){
        smallwin[index2].focus();
    } else {
        smallwin[index2] = window.open(url, index2,features);
    }
}

//Modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix
//  Original closewin() called .close() on cross-process Window references without
//  guards. On iPadOS 26.4/26.5 such calls throw SecurityError and, when fired
//  from the body 'unload' handler, the exception propagates along the opener
//  chain and kills the parent renderer ("this page can't be opened").
//  Fix: wrap every .close() in its own try/catch; also register a 'pagehide'
//  listener as a replacement for body onunload (unload is hostile to BFCache
//  and is being phased out across modern browsers).
//function closewin() {
//    for(var i=0; i<other_win.length;i++) {
//        if(other_win[i] !=null) {
//           other_win[i].close();
//        }
//    }
//    // for(var i=0; i<other_win_ebill.length;i++) {
//    //     if(other_win_ebill[i] !=null) {
//    //         other_win_ebill[i].close();
//    //     }
//    // }
//    for(var i in smallwin) {
//        if(smallwin[i] !=null) {
//            smallwin[i].close();
//        }
//    }
//
//    other_win_flag=0;
//
//    if (ebill_flag==1) {
//        if (!ebill_flag.closed) {
//            ebill_flag.close();
//            ebill_flag=0;
//        }
//    }
//}
function closewin() {
    try {
        for (var i = 0; i < other_win.length; i++) {
            var w = other_win[i];
            if (w) { try { if (!w.closed) w.close(); } catch (e) {} }
        }
    } catch (e) {}
    try {
        for (var i in smallwin) {
            var w = smallwin[i];
            if (w) { try { if (!w.closed) w.close(); } catch (e) {} }
        }
    } catch (e) {}

    other_win_flag = 0;

    try {
        if (ebill_flag == 1) {
            if (!ebill_flag.closed) {
                try { ebill_flag.close(); } catch (e) {}
                ebill_flag = 0;
            }
        }
    } catch (e) {}
}

// Replacement for body onunload=closewin(). Skip when going into BFCache.
try {
    window.addEventListener('pagehide', function (e) {
        if (e && e.persisted) return;
        try { closewin(); } catch (err) {}
    }, false);
} catch (e) {}
//End modified JCZhang 20260601 for iOS 26.4/26.5 (iPad Chrome / WKWebView) compatibility fix
/*
	Function: changeAccountView()
    - change view around corp and personal a/c
*/
function changeAccountView(subrNum, custNum, accountNum){
	window.location.href = 'LedMainGNV.jsp?subscriberNumber='+subrNum+'&customerNumber='+custNum+'&accountNumber='+accountNum;
}
</script>
<table border="0" width="<%= (!isPrepaid && !isNspHpp ? 1060 : 800) %>"
       cellspacing="1" cellpadding="1" vspace="1" hspace="1"  style='table-layout:fixed'>
    <% if (isNspHpp) { %>
    <!-- NSP HPP -->
    <col width=30>
    <col width=40>
    <col width=60>
    <col width=100>
    <col width=140>
    <col width=70>
    <col width=140>
    <col width=85>
    <% } else if (isPrepaid) { %>
    <!-- Prepaid -->
    <col width=60>
    <col width=140>
    <col width=80>
    <col width=70>
    <col width=170>
    <col width=85>
    <% } else { %>
    <!-- Postpaid -->
    <col width=30>
    <col width=40>
    <col width=95>
    <col width=150>
    <col width=80>
    <col width=70>
    <col width=65>
    <col width=185>
    <col width=85>
    <col width=130>
    <col width=120>
    <% } %>
    <tr> 
    <%if (!isPrepaid) {%>
        <td class=cell-label><%=lbl_bundle.getString("bill_dtl")%></td>
        <td class=cell-label><%=lbl_bundle.getString("bill_image")%></td>
    <%}%>
        <td class=cell-label><%=lbl_bundle.getString("date")%></td>
        <td class=cell-label><%=lbl_bundle.getString("reference")%></td>
        <td class=cell-label align="center"><%=lbl_bundle.getString("type")%></td>
        <td class=cell-label align="right"><%=lbl_bundle.getString("amount")%></td>
        <%if (!isPrepaid && !isNspHpp) {%>
        <td class=cell-label align="right"><%=lbl_bundle.getString("balance")%></td>
        <%}%>
        <td class=cell-label><%=lbl_bundle.getString("invoice_num")%></td>
        <%if (!isPrepaid && !isNspHpp) {%>
        <td class=cell-label><%=lbl_bundle.getString("alloca_date")%></td>
        <td class=cell-label><%=lbl_bundle.getString("complete_date")%></td>
        <%}%>
        <td class=cell-label><%=lbl_bundle.getString("Account_Num")%></td>
    </tr>
</table>

<!-- Content to be loaded dynamically from LedMainGNVContent.jsp -->
<table name=GNVRecordTable id=GNVRecordTable ></table> 

<table id=MessageTable border="0" width="<%= (!isPrepaid && !isNspHpp ? 1060 : 800) %>"
        cellspacing="1" cellpadding="1" vspace="1" hspace="1"  style='table-layout:fixed'></table>
</div>
</div>
</body>
</html>
<%	// Contact History -- For CS Users Only
        if (!custNumTemp.equals("") && !(isRestrictedRBD && isHigherBMCustomer)) {
            String ref_id = cust_num+userid.toString();
            CSFcmResult fcm_result = CSFcmResult.ins_fcm_insert_hist(conn,"BILLENQ",ref_id) ;
            int si = fcm_result.getResult();
            String err_msg = fcm_result.getMsg();
            if (si!=0) {
                System.out.println("LedMainGNV call fcm_insert_hist err: "+si+err_msg);
            }
        }

        if (salesid != null && !(isRestrictedRBD && isHigherBMCustomer)){
            sid = salesid.intValue();
            fes.helper.CustActLog.CustActHisIns(cust_num, subr_num, "CSLGR", sid, 'y', conn, log_user_id); 
        } else {
            fes.helper.CustActLog.CustActHisIns(cust_num, subr_num, "CSLGR", log_user_id, 'y', conn, log_user_id);
        }
    } catch(SQLException ee) {
        throw new ServletException(ee);
    } finally {
        if(rset!=null){try{rset.close();rset=null;}catch(Exception ignore){}}
        if(pstmt!=null){try{pstmt.close(); pstmt=null;}catch(Exception ignore){}}
        if(cstmt!=null){try{cstmt.close(); cstmt=null;}catch(Exception ignore){}}
        if(conn2!=null){try{pool2.freeConnection(conn2); conn2=null;}catch(Exception ignore){}}
        if(conn!=null){try{pool.freeConnection(conn); conn=null;}catch(Exception ignore){}}
        pool2 = null;
        pool = null;
    }  
%>