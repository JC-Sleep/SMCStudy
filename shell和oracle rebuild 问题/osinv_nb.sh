#!/usr/bin/ksh
#. /usr/users/fesjupld/config/GNV_environment
. $HOME/fes_config    ##(/appuat/workdir/fes/config/fes_config)

##################### JC Modify
export DL_CMD_GNV="/pos/download_cmd_GNV_NB"
##################### JC End
export OSINV="/pos/osinv"
export POS_DIR="/pos"
#####################################################
#  Part 2 of download_osinv_cmd.sh
#
#  Purpose: loading data to zz_pos_item
#####################################################
date_pattern=`date +%Y%m%d`

logd=${FES_LOG}${DL_CMD_GNV}
logf=$logd/download_osinv_cmd_${date_pattern}.log
WRKD=${FES_PROG}${DL_CMD_GNV}
OSINV_DIR=${FES_PROG}${OSINV}
#dbLogin=fesjupld
#dbPassword=fesjupld20
#dbPassword=fesjupld
CTL=${WRKD}/ctl
CTL_LOGDIR=${logd}/ctl
ERROR_LOG=${CTL_LOGDIR}"/osinv_cmd_error.log"
MAILLIST=9

FILE_DIR=${FES_INPUT}${POS_DIR}
FILE_SUFFIX=tmp
SALES_LEDGER_FILE=etl_pos_out.dat

UNLOAD_ZZ_POS_ITEM_FILE=$CTL/UNLOAD_ZZ_POS_ITEM.unl
UNLOAD_ZZ_POS_ITEM_ERR=$CTL_LOGDIR/UNLOAD_ZZ_POS_ITEM.err

LOCAL_DIR=${FES_INPUT}${POS_DIR}
UNLOAD_ZZ_POS_ITEM_OUT=${LOCAL_DIR}/SMC_pos_item_out_1.txt
UNLOAD_ZZ_POS_ITEM_OUT_BKUP=${LOCAL_DIR}/SMC_pos_item_out_1.txt.bk
#End modified by Ken Ip on 12 Aug 2010 for change the location to local drive
REG_TBL="zz_pos_item_load_reg"
v_load_ind="N"

if [ $# -eq 1 ]
then
   rerun=1
   v_load_ind="R"
   logf=$logd/rerun_osinv_cmd_${date_pattern}.log 
   echo "Rerun osinv.sh">${logf}
   if (test -s $WRKD'/rerun.log'); then
     ############# JC Comment out:  don't load SMC_pos_item_out_1.txt date to DB
#       if (test -s $UNLOAD_ZZ_POS_ITEM_OUT); then
#           mv $UNLOAD_ZZ_POS_ITEM_OUT $UNLOAD_ZZ_POS_ITEM_OUT_BKUP
#       fi
     ############# End JC Comment out:  don't load SMC_pos_item_out_1.txt date to DB
       echo "Part 2 - Loading ZZ_POS_ITEM Start at `date`">>${logf}       
   else
       echo "No Need Rerun. Abort at `date`">>${logf}
       exit
   fi
else
   echo "">>${logf}
   echo "Part 2 - Loading ZZ_POS_ITEM Start at `date`">>${logf}
   echo "Part 2 - Loading ZZ_POS_ITEM Start at `date`">>${POS_ITO_LOG}
fi
chmod 666 ${logf}

###################################################################
# Unload zz_pos_item in ('R','P','T') details to  SMC_pos_item_out_1.txt
###################################################################
############# JC Comment out:  don't load SMC_pos_item_out_1.txt date to DB
#echo -e "Unload pos item details at `date` ...\c" >> ${logf}
#echo -e "Unload pos item details at `date` ...\c">>${POS_ITO_LOG}
#
#cd $CTL
#. ${OSINV_DIR}/sqlunload_GNV.sh ${logf} $UNLOAD_ZZ_POS_ITEM_ERR $UNLOAD_ZZ_POS_ITEM_FILE $UNLOAD_ZZ_POS_ITEM_OUT ','
#
#echo ". ${OSINV_DIR}/sqlunload_GNV.sh ${logf} $UNLOAD_ZZ_POS_ITEM_ERR $UNLOAD_ZZ_POS_ITEM_FILE $UNLOAD_ZZ_POS_ITEM_OUT ','" >> ${logf}
#if [ $? -ne 0 ]; then
#   echo -e "\nERROR: osinv.sh: : unload pos item details ... FAILED `date`." >> ${logf}
#   echo -e "\nERROR: osinv.sh: : unload pos item details ... FAILED `date`." >> ${POS_ITO_LOG}
#   exit 1
#fi
##echo "Done" >> ${logf}
##add time stamp by Ken Ip on 12 Aug 2010
#echo "Done at `date`." >> ${logf}
##End add time stamp by Ken Ip on 12 Aug 2010
############# End JC Comment out:  don't load SMC_pos_item_out_1.txt date to DB
################# JC Comment out
## ###################################################################
## # Determine Current Physical Table under ZZ_POS_ITEM VIEW
## ###################################################################
## cd $CTL
## echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_POS_ITEM_CMD.log
## echo "SET SERVEROUTPUT ON" >> DOWNLOAD_POS_ITEM_CMD.log
## echo "EXEC cn_osinv.get_cur_tname('ZZ_POS_ITEM','${logf}');" >> DOWNLOAD_POS_ITEM_CMD.log
## echo "EXIT" >> DOWNLOAD_POS_ITEM_CMD.log
##
## sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log > t1name.log
## grep "ZZ_POS_ITEM" t1name.log > a1.log
## if [ $? -ne 0 ]; then
##    echo "Error: Storeproc cn_osinv.get_cur_tname(zz_pos_item)."
##    echo "ERROR: (osinv.sh) Storeproc cn_osinv.get_cur_tname(zz_pos_item). Please contact FES Support Team." >> ${POS_ITO_LOG}
##    exit 1
## fi
##
## if ( test -s a1.log )
## then
##    ZZ_POS_ITEM_TBL=`cat a1.log`
##    echo "Current Table Used: $ZZ_POS_ITEM_TBL"
## else
##    echo "Error: Unable to get current ZZ_POS_ITEM table name."
##    echo "ERROR: (osinv.sh) Unable to get current ZZ_POS_ITEM table name. Please contact FES Support Team." >> ${POS_ITO_LOG}
##    exit 1
## fi
##
## if ( test $ZZ_POS_ITEM_TBL = 'ZZ_POS_ITEM_1' )
## then
##    ZZ_POS_ITEM_BK_TBL='ZZ_POS_ITEM_2'
## else
##    ZZ_POS_ITEM_BK_TBL='ZZ_POS_ITEM_1'
## fi
##
## echo "Backup table is $ZZ_POS_ITEM_BK_TBL" >> ${logf}

ZZ_POS_ITEM_BK_TBL='ZZ_POS_ITEM_NB'
echo "Backup table is $ZZ_POS_ITEM_BK_TBL" >> ${logf}
################# End JC Comment out


##############################################################
# Truncate ZZ_POS_ITEM_BK
##############################################################
cd $CTL
echo -e "Truncate Table $ZZ_POS_ITEM_BK_TBL ... \c">>${logf}
echo -e "Truncate Table $ZZ_POS_ITEM_BK_TBL ... \c">>${POS_ITO_LOG}
echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_POS_ITEM_CMD.log
echo "EXEC cn_lib.onpload_trunc_tbl('${ZZ_POS_ITEM_BK_TBL}');" >> DOWNLOAD_POS_ITEM_CMD.log
echo "EXIT" >> DOWNLOAD_POS_ITEM_CMD.log
sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log > ${ERROR_LOG}
if [ $? -ne 0 ]; then
   echo "Error:(osinv.sh): Truncate $ZZ_POS_ITEM_BK_TBL failed " >> ${logf}
   echo "ERROR:(osinv.sh): Truncate $ZZ_POS_ITEM_BK_TBL failed. Please contact FES team." >> ${POS_ITO_LOG}
   #trigger_email_alert "Error: (osinv.sh): Truncate $ZZ_POS_ITEM_BK_TBL failed." $MAILLIST
   exit 1
fi
echo "Done">>${logf}
echo "Done">>${POS_ITO_LOG}


##############################################################
# load SMC_pos_item_out_1.txt to zz_pos_item details
##############################################################
################# JC Comment out
##added new log message by Ken Ip on 12 Aug 2010
#echo "Start running dbloadospos_part1 at `date`." >> ${logf}
##End added new log message by Ken Ip on 12 Aug 2010
#result=`. $WRKD'/dbloadospos_part1' $UNLOAD_ZZ_POS_ITEM_OUT $ZZ_POS_ITEM_BK_TBL`
#if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
#then
#   echo "Error:(osinv.sh): Failed to load $UNLOAD_ZZ_POS_ITEM_OUT into $ZZ_POS_ITEM_BK_TBL" >> $logf
#   echo "ERROR:(osinv.sh): Failed to load $UNLOAD_ZZ_POS_ITEM_OUT into $ZZ_POS_ITEM_BK_TBL" >> ${POS_ITO_LOG}
#   #trigger_email_alert "Error: (osinv.sh): Failed to load $UNLOAD_ZZ_POS_ITEM_OUT into $ZZ_POS_ITEM_BK_TBL" $MAILLIST
#   exit 1
#fi
##added new log message by Ken Ip on 12 Aug 2010
#echo "End running dbloadospos_part1 at `date`." >> ${logf}
##End added new log message by Ken Ip on 12 Aug 2010
################# End JC Comment out

############################################################
# Format the Input File
############################################################
cd $FILE_DIR
tmp_file=$SALES_LEDGER_FILE'_'$FILE_SUFFIX
echo "awk -f $WRKD/osinv_format.awk $SALES_LEDGER_FILE > $tmp_file at `date`" >> $logf
awk -f $WRKD/osinv_format.awk $SALES_LEDGER_FILE > $tmp_file

############################################################
# Load File into zz_pos_item
############################################################
echo "Star loading $tmp_file to $ZZ_POS_ITEM_BK_TBL at `date`" >> ${logf}
result=`. $WRKD'/dbloadospos_part2' $FILE_DIR/$tmp_file $ZZ_POS_ITEM_BK_TBL`
if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
then
   echo "Error:(osinv.sh): Failed to load $tmp_file into $ZZ_POS_ITEM_BK_TBL" >> $logf
   echo "ERROR:(osinv.sh): Failed to load $tmp_file into $ZZ_POS_ITEM_BK_TBL. Please contact FES team." >> ${POS_ITO_LOG}
   #trigger_email_alert "Error: (osinv.sh): Failed to load $tmp_file into $ZZ_POS_ITEM_BK_TBL" $MAILLIST
   exit 1
fi


#############################################################
# Rebuild Index 
#############################################################
###cd $CTL
###echo "Start Rebuild Index at $ZZ_POS_ITEM_BK_TBL ... \c" >> ${logf}
###echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_POS_ITEM_CMD.log
###echo "SET SERVEROUTPUT ON " >> DOWNLOAD_POS_ITEM_CMD.log
###echo "EXEC cn_lib.rebuild_index('${ZZ_POS_ITEM_BK_TBL}'); " >> DOWNLOAD_POS_ITEM_CMD.log
###echo "EXIT" >> DOWNLOAD_POS_ITEM_CMD.log

###echo "" > ${ERROR_LOG}
###sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
###result=`grep "ERROR" ${ERROR_LOG}`

###if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
###then
###  ##########################################
###  ### Try again to rebuild index
###  ##########################################
###  echo "rebuild index again">>${logf}
###  sleep 60  
###  echo "" > ${ERROR_LOG}
###  sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
###  result=`grep "ERROR" ${ERROR_LOG}`

###  if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
###  then
###    echo "Error:(osinv.sh): Rebuild Index $ZZ_POS_ITEM_BK_TBL failed " >> $logf
###    echo "Error:(osinv.sh): Rebuild Index $ZZ_POS_ITEM_BK_TBL failed" >> ${POS_ITO_LOG} 
###    trigger_email_alert "Error: (osinv.sh): Rebuild Index $ZZ_POS_ITEM_BK_TBL failed." $MAILLIST
###    exit 1
###  fi
###else
###  echo "Finish rebuild index">>${logf}
###fi
###echo "Done">>$logf

#############################################################
# Exit the job if the job running time is too late
############################################################
currentTime=`date +%H%M`
#test
#if [ $currentTime -gt 750 ]
#then
#    echo "Error:(osinv.sh): Update table zz_pos_item cannot be completed on/before 0750. O/S balance is one day behind" >> $logf
#    echo "ERROR:(osinv.sh): Update table zz_pos_item cannot be completed on/before 0750. O/S balance is one day behind" >> ${POS_ITO_LOG}
#    exit 1
#fi
#End test
#############################################################
# Update Statistics $ZZ_POS_ITEM_BK_TBL
#############################################################
cd $CTL
echo "Start Update Statistics $ZZ_POS_ITEM_BK_TBL at `date`" >> ${logf}
echo "Start Update Statistics $ZZ_POS_ITEM_BK_TBL `date`" >> ${POS_ITO_LOG}
echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_POS_ITEM_CMD.log
echo "EXEC cn_lib.update_statistics('$ZZ_POS_ITEM_BK_TBL');">> DOWNLOAD_POS_ITEM_CMD.log
echo "EXIT" >> DOWNLOAD_POS_ITEM_CMD.log
sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
if [ $? -ne 0 ]; then
  ##########################################
  ### Try again to Update statistics
  ##########################################
  echo "Update statistics again">>${logf}
  sleep 60
  sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
  if [ $? -ne 0 ]; then
    echo "Error:(osinv.sh): Update Statistics $ZZ_POS_ITEM_BK_TBL failed " >> $logf
    echo "ERROR:(osinv.sh): Update Statistics $ZZ_POS_ITEM_BK_TBL failed . Please contact FES team." >> ${POS_ITO_LOG}
    #trigger_email_alert "Error: (osinv.sh): Update Statistics $ZZ_POS_ITEM_BK_TBL failed." $MAILLIST
    exit 1
  fi
else
  echo "Finish Update statistics">>${logf}
fi
echo "Done">>$logf
#echo "Done">>${POS_ITO_LOG}

################# JC Comment out
## #############################################################
## # Build View Table ZZ_POS_ITEM
## #############################################################
## cd $CTL
## echo -e "\nBuild View Table on $ZZ_POS_ITEM_BK_TBL Start at `date` ...\c" >> ${logf}
##
## echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_POS_ITEM_CMD.log
## echo "EXEC cn_osinv.build_view_pos_item('${ZZ_POS_ITEM_BK_TBL}');" >> DOWNLOAD_POS_ITEM_CMD.log
## echo "EXIT" >> DOWNLOAD_POS_ITEM_CMD.log
##
## sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
## if [ $? -ne 0 ]; then
##    ##########################################
##    ### Try again to build view
##    ##########################################
##    echo "build view again">>${logf}
##    sleep 60
##    sqlplus $ORA_LOGNAME @DOWNLOAD_POS_ITEM_CMD.log >> ${ERROR_LOG}
##    if [ $? -ne 0 ]; then
##      echo "ERROR: osinv.sh : Build View on $ZZ_POS_ITEM_BK_TBL ... FAILED `date`." >> ${logf}
##      echo "ERROR: osinv.sh : Build View on $ZZ_POS_ITEM_BK_TBL ... FAILED `date`.">>${POS_ITO_LOG}
##      #trigger_email_alert "ERROR: osinv.sh : Build View on $ZZ_POS_ITEM_BK_TBL ... FAILED `date`." $MAILLIST
##      exit 1
##    fi
## else
##   echo "Finish build view">>${logf}
## fi
## echo "Done at `date`" >> ${logf}
## ##
## #################################
##
## #############################################################
## # Oracle 10g
## #############################################################
## #echo "ORACLE_SID="${ORACLE_SID} >> ${logf}
## echo -e "\nExecute crt_vw at `date` ...\c" >> ${logf}
##
## sqlplus -s $ORA_LOGNAME << EOF 1>> ${logf}
## DECLARE
##     count integer;
## BEGIN
##     count := crt_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}');
## END;
## /
## EOF
## if [ $? -ne 0 ]; then
##    echo "ERROR: osinv.sh : Execute crt_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." >> ${logf}
##    #trigger_email_alert "ERROR: osinv.sh : Execute crt_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." $MAILLIST
##    exit 1
## fi
##
## echo -e "\nExecute crt_ezp_vw at `date` ...\c" >> ${logf}
##
## sqlplus -s $ORA_LOGNAME << EOF 1>> ${logf}
## DECLARE
##     count integer;
## BEGIN
##     count := crt_ezp_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}');
## END;
## /
## EOF
## if [ $? -ne 0 ]; then
##    echo "ERROR: osinv.sh : Execute crt_ezp_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." >> ${logf}
##    #trigger_email_alert "ERROR: osinv.sh : Execute crt_ezp_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." $MAILLIST
##    exit 1
## fi
##
##
##
## echo -e "\nExecute crt_cams_vw at `date` ...\c" >> ${logf}
##
## sqlplus -s $ORA_LOGNAME << EOF 1>> ${logf}
## DECLARE
##     count integer;
## BEGIN
##     count := crt_cams_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}');
## END;
## /
## EOF
## if [ $? -ne 0 ]; then
##    echo "ERROR: osinv.sh : Execute crt_cams_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." >> ${logf}
##    #trigger_email_alert "ERROR: osinv.sh : Execute crt_cams_vw('ZZ_POS_ITEM', '${ZZ_POS_ITEM_BK_TBL}') FAILED at `date`." $MAILLIST
##    exit 1
## fi
##
#################################
################# End JC Comment out

################################################
### Insert a record into zz_pos_item_load_reg
### when view table successfully created
################################################
################# JC Comment out
#cd $CTL
#echo "Insert record into ${REG_TBL}">>${logf}
#echo " WHENEVER SQLERROR EXIT SQL.SQLCODE " > Insert_Reg.log
#echo " Insert into ${REG_TBL} " >> Insert_Reg.log
#echo " (load_date, load_ind) " >> Insert_Reg.log
#echo " values " >> Insert_Reg.log
#echo " (sysdate, '${v_load_ind}'); " >> Insert_Reg.log
#echo " EXIT " >> Insert_Reg.log
#
#sqlplus $ORA_LOGNAME @Insert_Reg.log >> ${ERROR_LOG}
#if [ $? -ne 0 ]; then
#   ##########################################
#   ### Try again to Insert record
#   ##########################################
#   echo "Insert record again">>${logf}
#   sleep 60
#   sqlplus $ORA_LOGNAME @Insert_Reg.log >> ${ERROR_LOG}
#   if [ $? -ne 0 ]; then
#     echo "ERROR: osinv.sh : Insert record into ${REG_TBL} FAILED." >> ${logf}
#     #trigger_email_alert "ERROR: osinv.sh : Insert record into ${REG_TBL} FAILED. LOAD_IND should be ${v_load_ind} at `date`" $MAILLIST
#   fi
#else
#  echo "Finish Insert record to ${REG_TBL}">>${logf}
#fi
#echo "Done" >> ${logf}
################# End JC Comment out

echo "Part 2 - Loading ZZ_POS_ITEM Finished at `date`">>${logf}
echo "Part 2 - Loading ZZ_POS_ITEM Finished at `date`">>${POS_ITO_LOG}

################# JC Comment out
## ## Call Update upd_osinv.sh
## echo ". $WRKD/upd_osinv_nb.sh" >>${logf}
## . $WRKD/upd_osinv_nb.sh $logf

################# End JC Comment out
