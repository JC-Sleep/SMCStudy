#!/usr/bin/ksh
#. /usr/users/fesjupld/config/GNV_environment
. $HOME/fes_config    ##(/appuat/workdir/fes/config/fes_config)

##################### JC Modify
export DL_CMD_GNV="/pos/download_cmd_GNV_NB"
##################### JC End

export OSINV="/pos/osinv"
export POS_DIR="/pos"
#####################################################
#  Part 2 of download_custpro_cmd.sh
#
#  Purpose: loading data to ZZ_PSUB_REF
#####################################################
TODAY=`date +'%d'`
CURRENT_DATETIME=`date +'%Y%m%d'`
date_pattern=`date +%Y%m%d`

#logd="/usr/users/fesjupld/log/pos"
logd=${FES_LOG}${DL_CMD_GNV}
logf=$logd/download_custpro_cmd_${date_pattern}.log 
#WRKD="/usr/users/fesjupld/cronjob/pos/download_cmd_GNV"
WRKD=${FES_PROG}${DL_CMD_GNV}
#OSINV_DIR="/usr/users/fesjupld/cronjob/pos/osinv"
OSINV_DIR=${FES_PROG}${OSINV}

MAILLIST=9

#FILE_DIR="/nfs/export/pos"
FILE_DIR=${FES_INPUT}${POS_DIR}
FILE_SUFFIX=tmp
SUBR_INFO_FILE=etl_subr_info.txt
#Added by Michael Wong on 26 Oct 2015 for using SFTP
SUBR_INFO_FILE_TMP=$SUBR_INFO_FILE'_'$FILE_SUFFIX
#End added by Michael Wong on 26 Oct 2015 for using SFTP

CTL=${WRKD}/ctl
#CTL_LOGDIR="/nfs/export/pos/log"
CTL_LOGDIR=${logd}"/ctl"
#ERROR_LOG=$CTL/custpro_cmd_error.log
ERROR_LOG=${CTL_LOGDIR}"/custpro_cmd_error.log"
############# JC Comment out
#REG_TBL="zz_psub_ref_load_reg"
############# End JC Comment out
v_load_ind="N"

if [ $# -eq 1 ]
then
   v_load_ind="R"
   logf=$logd/rerun_download_custpro_cmd_${date_pattern}.log 
   echo "Rerun custpro.sh">${logf}
   if (test -s $WRKD'/rerun_cust.log'); then
       echo "Part 2 - Loading ZZ_PSUB_REF Start at `date`">>${logf}       
   else
       echo "No Need Rerun. Abort at `date`">>${logf}
       exit
   fi
else
   echo "">>${logf}
   echo "Part 2 - Loading ZZ_PSUB_REF Start at `date`">> ${logf}
   echo "Part 2 - Loading ZZ_PSUB_REF Start at `date`">> ${POS_ITO_LOG}
fi
chmod 666 ${logf}

################# JC Comment out
###################################################################
# Determine Current Physical Table under ZZ_PSUB_REF VIEW
###################################################################
##cd $CTL
##echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
##echo "SET SERVEROUTPUT ON" >> DOWNLOAD_PSUB_REF_CMD.log
##echo "EXEC cn_osinv.get_cur_tname('ZZ_PSUB_REF','${logf}');" >> DOWNLOAD_PSUB_REF_CMD.log
##echo "EXIT" >> DOWNLOAD_PSUB_REF_CMD.log
##
##sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log > t2name.log
##grep "ZZ_PSUB_REF" t2name.log > a2.log
##if [ $? -ne 0 ]; then
##   echo "Error: Storeproc cn_osinv.get_cur_tname(zz_psub_ref)." >> ${logf}
##   echo "ERROR: (custpro.sh) Storeproc cn_osinv.get_cur_tname(zz_psub_ref). Please contact FES Support Team." >> ${POS_ITO_LOG}
##   exit 1
##fi
##
##if ( test -s a2.log )
##then
##   ZZ_PSUB_REF_TBL=`cat a2.log`
##   echo "Current Table Used: $ZZ_PSUB_REF_TBL"
##else
##   echo "Error: Unable to get current ZZ_PSUB_REF table name." >> ${logf}
##   echo "ERROR: (custpro.sh) Unable to get current ZZ_PSUB_REF table name. Please contact FES Support Team." >> ${POS_ITO_LOG}
##   exit 1
##fi
##
##if ( test $ZZ_PSUB_REF_TBL = 'ZZ_PSUB_REF_1' )
##then
##   ZZ_PSUB_REF_BK_TBL='ZZ_PSUB_REF_2'
##else
##   ZZ_PSUB_REF_BK_TBL='ZZ_PSUB_REF_1'
##fi
##
##echo "Backup table is $ZZ_PSUB_REF_BK_TBL" >> ${logf}

##################### JC End

##################### JC Added

ZZ_PSUB_REF_BK_TBL="ZZ_PSUB_REF_NB"
echo "Current table is $ZZ_PSUB_REF_BK_TBL" >> ${logf}
##################### JC End

##############################################################
# Truncate ZZ_PSUB_REF_BK
##############################################################
cd $CTL
echo -e "Truncate Table $ZZ_PSUB_REF_BK_TBL ... \c">>${logf}
#echo "Truncate Table $ZZ_PSUB_REF_BK_TBL ... \c">>${POS_ITO_LOG}
echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
echo "EXEC cn_lib.onpload_trunc_tbl('${ZZ_PSUB_REF_BK_TBL}');" >> DOWNLOAD_PSUB_REF_CMD.log
echo "EXIT" >> DOWNLOAD_PSUB_REF_CMD.log

sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log > ${ERROR_LOG}
if [ $? -ne 0 ]; then
  echo "Error:(custpro.sh): Truncate $ZZ_PSUB_REF_BK_TBL failed " >> ${logf}
  echo "ERROR:(custpro.sh): Truncate $ZZ_PSUB_REF_BK_TBL failed. Please contact FES team." >> ${POS_ITO_LOG}
  #trigger_email_alert "Error: (custpro.sh): Truncate $ZZ_PSUB_REF_BK_TBL failed." $MAILLIST
  exit 1
fi
echo "Done">>${logf}
#echo "Done">>${POS_ITO_LOG}

############################################################
# Format the Input File
############################################################
cd $FILE_DIR
tmp_file=$SUBR_INFO_FILE'_'$FILE_SUFFIX
echo "awk -f $WRKD/custpro_format.awk $SUBR_INFO_FILE > $tmp_file" >> $logf
awk -f $WRKD/custpro_format.awk $SUBR_INFO_FILE > $tmp_file

############################################################
# Load File into zz_psub_ref
############################################################
echo "Star loading $tmp_file to $ZZ_PSUB_REF_BK_TBL" >> ${logf}
result=`. $WRKD'/dbloadsr' $FILE_DIR/$tmp_file $ZZ_PSUB_REF_BK_TBL`
if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
then
   echo "Error:(custpro.sh): Failed to load $tmp_file into $ZZ_PSUB_REF_BK_TBL" >> $logf
   echo "ERROR:(custpro.sh): Failed to load $tmp_file into $ZZ_PSUB_REF_BK_TBL. Please contact FES team." >> ${POS_ITO_LOG}
   #trigger_email_alert "Error: (custpro.sh): Failed to load $tmp_file into $ZZ_PSUB_REF_BK_TBL" $MAILLIST
   exit 1
fi

#############################################################
# Rebuild Index 
#############################################################
###cd $CTL
###echo "Start Rebuild Index at $ZZ_PSUB_REF_BK_TBL ... \c" >> ${logf}
###echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
###echo "SET SERVEROUTPUT ON " >> DOWNLOAD_PSUB_REF_CMD.log
###echo "EXEC cn_lib.rebuild_index('${ZZ_PSUB_REF_BK_TBL}'); " >> DOWNLOAD_PSUB_REF_CMD.log
###echo "EXIT" >> DOWNLOAD_PSUB_REF_CMD.log

###echo "" > ${ERROR_LOG}
###sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log >> ${ERROR_LOG}
###result=`grep "ERROR" ${ERROR_LOG}`

###if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
###then
###  ##########################################
###  ### Try again to rebuild index
###  ##########################################
###  echo "rebuild index again">>${logf}
###  sleep 60  
###  echo "" > ${ERROR_LOG}
###  sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log >> ${ERROR_LOG}
###  result=`grep "ERROR" ${ERROR_LOG}`

###  if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
###  then
###     echo "Error:(custpro.sh): Rebuild Index $ZZ_PSUB_REF_BK_TBL failed " >> ${logf}
###     echo "Error:(custpro.sh): Rebuild Index $ZZ_PSUB_REF_BK_TBL failed " >> ${POS_ITO_LOG}
###     trigger_email_alert "Error: (custpro.sh): Rebuild Index $ZZ_PSUB_REF_BK_TBL failed." $MAILLIST
###     exit 1
###  fi
###else
###  echo "Finish rebuild index">>${logf}
###fi

###echo "Done">>${logf}

#############################################################
# Update Statistics ZZ_PSUB_REF_BK
#############################################################
cd $CTL
echo "Start Update Statistics $ZZ_PSUB_REF_BK_TBL at `date`" >> ${logf}
#echo "Start Update Statistics $ZZ_PSUB_REF_BK_TBL `date`" >> ${POS_ITO_LOG}
echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
echo "EXEC cn_lib.update_statistics('$ZZ_PSUB_REF_BK_TBL');">> DOWNLOAD_PSUB_REF_CMD.log
echo "EXIT" >> DOWNLOAD_PSUB_REF_CMD.log
sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log >> ${ERROR_LOG}
if [ $? -ne 0 ]; then
  ##########################################
  ### Try again to Update statistics
  ##########################################
  echo "Update statistics again">>${logf}
  sleep 60
  sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log >> ${ERROR_LOG}
  if [ $? -ne 0 ]; then
    echo "Error:(custpro.sh): Update Statistics $ZZ_PSUB_REF_BK_TBL failed " >> ${logf}
    echo "ERROR:(custpro.sh): Update Statistics $ZZ_PSUB_REF_BK_TBL failed . Please contact FES team." >> ${POS_ITO_LOG}
    #trigger_email_alert "Error: (custpro.sh): Update Statistics $ZZ_PSUB_REF_BK_TBL failed." $MAILLIST
    exit 1
  fi
else
  echo "Finish Update statistics">>${logf}
fi
echo "Done">>${logf}
#echo "Done">>${POS_ITO_LOG}

# Ken Kam Place the code for updating ceiling unbilled amount here
#1. truncate temp ceiling table
#2. call awk command to reformat the data file by adding decimal point for field data usage and ceiling amount
#3. sqlonpload to load the ceiling data
#4. call Store proc to update ceilig unbill amount to zz_psub_ref_1 / 2
#added by Ken Ip on 29 Oct 2008 for updating ceiling unbilled amount
##############################################################
# Truncate temp ceiling table : POSM_DDE_UNBILL
##############################################################
#Modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS
#UNBILL_TEMP_TABLE='POSM_DDE_UNBILL'
#cd $CTL
#echo -e "Truncate Table $UNBILL_TEMP_TABLE ... \c">>${logf}
#echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_UNBILL_CMD.log
#echo "EXEC cn_lib.onpload_trunc_tbl('${UNBILL_TEMP_TABLE}');" >> DOWNLOAD_UNBILL_CMD.log
#echo "EXIT" >> DOWNLOAD_UNBILL_CMD.log
#
#sqlplus $ORA_LOGNAME @DOWNLOAD_UNBILL_CMD.log > ${ERROR_LOG}
#if [ $? -ne 0 ]; then
#  echo "Error:(custpro.sh): Truncate $UNBILL_TEMP_TABLE failed " >> ${logf}
#  echo "ERROR:(custpro.sh): Truncate $UNBILL_TEMP_TABLE failed. Please contact FES team." >> ${POS_ITO_LOG}
#  #trigger_email_alert "Error: (custpro.sh): Truncate $UNBILL_TEMP_TABLE failed." $MAILLIST
#  exit 1
#fi
#echo "Done">>${logf}
#End modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS

############################################################
# Format the unbill data File
############################################################
#Modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS
#CEIL_FILE_DATA=GPRS_Unbill_Data.txt
#cd $FILE_DIR
#tmp_file=$CEIL_FILE_DATA'_'$FILE_SUFFIX
#echo "awk -f $WRKD/unbill_ceiling_format.awk $CEIL_FILE_DATA > $tmp_file" >> $logf
#awk -f $WRKD/unbill_ceiling_format.awk $CEIL_FILE_DATA > $tmp_file
#End modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS

############################################################
# Load File into temp ceiling table : POSM_DDE_UNBILL
############################################################
#Modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS
#echo "Star loading $tmp_file to $UNBILL_TEMP_TABLE" >> ${logf}
#result=`. $WRKD'/dbload_unbill_ceiling' $FILE_DIR/$tmp_file $UNBILL_TEMP_TABLE`
#if [ `echo $result | grep ERROR | wc -l` -gt 0 ]
#then
#  echo "Error:(custpro.sh): Failed to load $tmp_file into $UNBILL_TEMP_TABLE" >> $logf
#  echo "ERROR:(custpro.sh): Failed to load $tmp_file into $UNBILL_TEMP_TABLE. Please contact FES team." >> ${POS_ITO_LOG}
#  #trigger_email_alert "Error: (custpro.sh): Failed to load $tmp_file into $UNBILL_TEMP_TABLE" $MAILLIST
#  exit 1
#fi
#End modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS

#############################################################
# Update unbill amount of ZZ_PSUB_REF_BK
#############################################################
#Modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS
#cd $CTL
#echo "Start Update Unbill Amount $ZZ_PSUB_REF_BK_TBL at `date`" >> ${logf}
#echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_UNBILL_CMD.log
#echo "EXEC cn_osinv.update_ceiling_unbill('${ZZ_PSUB_REF_BK_TBL}','${logf}');">> DOWNLOAD_UNBILL_CMD.log
#echo "EXIT" >> DOWNLOAD_UNBILL_CMD.log
#sqlplus $ORA_LOGNAME @DOWNLOAD_UNBILL_CMD.log >> ${ERROR_LOG}
#if [ $? -ne 0 ]; then
#  echo "Error:(custpro.sh): Update Unbill Amount $ZZ_PSUB_REF_BK_TBL failed " >> ${logf}
#  echo "ERROR:(custpro.sh): Update Unbill Amount $ZZ_PSUB_REF_BK_TBL failed . Please contact FES team." >> ${POS_ITO_LOG}
#  #trigger_email_alert "Error: (custpro.sh): Update Unbill Amount $ZZ_PSUB_REF_BK_TBL failed." $MAILLIST
#  exit 1
#else
#  echo "Finish Update Unbill Amount">>${logf}
#fi
#End modified by JCZhang on 2025-08-14 for DDE file SIT to comment out unbill GPRS

##################### JC Comment out
#############################################################
# Build View Table ZZ_PSUB_REF
#############################################################
## cd $CTL
## echo -e "\nBuild View Table $ZZ_PSUB_REF_BK_TBL Start at `date` ...\c" >> ${logf}
##
## echo "WHENEVER SQLERROR EXIT SQL.SQLCODE" > DOWNLOAD_PSUB_REF_CMD.log
## echo "EXEC cn_osinv.build_view_psub_ref('${ZZ_PSUB_REF_BK_TBL}');" >> DOWNLOAD_PSUB_REF_CMD.log
## echo "EXIT" >> DOWNLOAD_PSUB_REF_CMD.log
##
## sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log > ${ERROR_LOG}
## if [ $? -ne 0 ]; then
##    ##########################################
##    ### Try again to build view
##    ##########################################
##    echo "build view again">>${logf}
##    sleep 60
##    sqlplus $ORA_LOGNAME @DOWNLOAD_PSUB_REF_CMD.log > ${ERROR_LOG}
##    if [ $? -ne 0 ]; then
##      echo "ERROR: custpro.sh : Build View Table $ZZ_PSUB_REF_BK_TBL ... FAILED `date`." >> ${logf}
##      echo "ERROR: custpro.sh : Build View Table $ZZ_PSUB_REF_BK_TBL ... FAILED `date`." >> ${POS_ITO_LOG}
##      #trigger_email_alert "ERROR: custpro.sh : Build View Table $ZZ_PSUB_REF_BK_TBL ... FAILED `date`." $MAILLIST
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
## echo -e "\nExecute crt_vw at `date` ...\c" >> ${logf}
##
## sqlplus -s $ORA_LOGNAME << EOF 1>> ${logf}
## DECLARE
##     count integer;
## BEGIN
##     count := crt_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}');
## END;
## /
## EOF
## if [ $? -ne 0 ]; then
##    echo "ERROR: custpro.sh : Execute crt_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}') FAILED at `date`." >> ${logf}
##    #trigger_email_alert "ERROR: custpro.sh : Execute crt_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}') FAILED at `date`." $MAILLIST
##    exit 1
## fi
##
## echo -e "\nExecute crt_cams_vw at `date` ...\c" >> ${logf}
##
## sqlplus -s $ORA_LOGNAME << EOF 1>> ${logf}
## DECLARE
##     count integer;
## BEGIN
##     count := crt_cams_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}');
## END;
## /
## EOF
## if [ $? -ne 0 ]; then
##    echo "ERROR: custpro.sh : Execute crt_cams_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}') FAILED at `date`." >> ${logf}
##    #trigger_email_alert "ERROR: custpro.sh : Execute crt_cams_vw('ZZ_PSUB_REF', '${ZZ_PSUB_REF_BK_TBL}') FAILED at `date`." $MAILLIST
##    exit 1
## fi
##
##################### JC End

##################### JC Comment out : seem to be not needed comment out,because record for successful created
#################################################
#### Insert a record into zz_psub_ref_load_reg
#### when view table successfully created
#################################################
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
#     #trigger_email_alert "ERROR: custpro.sh : Insert record into ${REG_TBL} FAILED. LOAD_IND should be ${v_load_ind} at `date`" $MAILLIST
#   fi
#else
#  echo "Finish Insert record to ${REG_TBL}">>${logf}
#fi
#echo "Done" >> ${logf}
##################### JC End

##
#################################

#Added by Michael Wong on 26 Oct 2015 for using SFTP
rm -r ${FES_INPUT}${SUBR_INFO_FILE_TMP}
rm -f ${FES_INPUT}${SUBR_INFO_FILE}
#End addd by Michael Wong on 26 Oct 2015 for using SFTP

echo "Part 2 - Loading ZZ_PSUB_REF End at `date`">>${logf}
echo "Part 2 - Loading ZZ_PSUB_REF End at `date`">>${POS_ITO_LOG}

echo "download_custpro_cmd End at `date`" >> ${logf}
echo "download_custpro_cmd End at `date`" >> ${POS_ITO_LOG}
